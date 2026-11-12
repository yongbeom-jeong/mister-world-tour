"""SMS Notification 서비스 (설계 09장 SMSNotificationManager · SMSServiceClient · SMSMessage).

- 수신 대상 선정과 출발 확정 판정은 Travel Management 가 한다. 여기서는 받은 대상에게 보내기만 한다.
- 최초 발송 1회 + 명확한 실패 시 최대 3회 재시도 (전체 최대 4회 외부 호출).
- 결과 불명(응답 시간 초과 등)은 실패로 바꿔 즉시 재발송하지 않는다 (중복 문자 방지).
- 신청 요청 안에서 보내므로 한 번의 발송 묶음 전체에 시간 한도(settings.SMS_BATCH_TIME_LIMIT)를 둔다.
  한도 안에 끝나지 못할 시도는 하지 않고 실패로 남긴다 (gunicorn 시간 초과로 화면이 502가 되는 것 방지).
- 수신자마다 발송 기록(SMSLog)을 남긴다. 보내기 전에 '발송 중'으로 저장하고 결과로 바꾼다.
  기록 저장이 실패해도 문자 발송은 계속한다. 직원은 실패·결과 불명 기록을 보고 다시 보낼 수 있다.
- settings.SMS_CLIENT 로 교체한다. 기본은 콘솔(로그)로 보내는 가짜 클라이언트,
  실제 발송은 솔라피 (apps/sms/solapi.py 의 SolapiSMSClient).
"""

import logging
import time
from dataclasses import dataclass, field
from datetime import timedelta
from enum import Enum

from django.conf import settings
from django.db import transaction
from django.db.models import Q
from django.utils import timezone
from django.utils.module_loading import import_string

from .models import STALE_PENDING_MINUTES, SMSLog

logger = logging.getLogger(__name__)
MAX_ATTEMPTS = 4  # 최초 1회 + 재시도 3회


class SMSDeliveryStatus(str, Enum):
    PENDING = "PENDING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"
    INVALID = "INVALID"  # 필수 정보 누락으로 외부 호출 전 거부


@dataclass
class SMSRecipient:
    member_id: int
    name: str
    contact: str


@dataclass
class DepartureNotice:
    product_id: int
    product_name: str
    departure_date: object  # date

    def message(self, recipient: SMSRecipient) -> str:
        """단문(SMS, 90byte) 안에 들어가도록 만든다. 길면 이름 → 상품 이름 순으로 줄인다."""
        d = self.departure_date
        when = f"{d.month}월 {d.day}일"
        full = f"{SMS_PREFIX} {recipient.name}님, {self.product_name} {when} 출발 확정되었습니다."
        if sms_bytes(full) <= SMS_MAX_BYTES:
            return full
        tail = f" {when} 출발 확정"
        short = f"{SMS_PREFIX} {self.product_name}{tail}"
        if sms_bytes(short) <= SMS_MAX_BYTES:
            return short
        budget = SMS_MAX_BYTES - sms_bytes(f"{SMS_PREFIX} ..{tail}")
        product = ""
        for ch in self.product_name:
            if sms_bytes(product + ch) > budget:
                break
            product += ch
        return f"{SMS_PREFIX} {product}..{tail}"


SMS_PREFIX = "[미스터월드투어]"
SMS_MAX_BYTES = 90  # 단문 한도 (영문·숫자·공백 1byte, 한글 2byte)


def sms_bytes(text: str) -> int:
    return sum(1 if ord(ch) < 128 else 2 for ch in text)


@dataclass
class SMSMessage:
    recipient: SMSRecipient
    message: str
    status: SMSDeliveryStatus = SMSDeliveryStatus.PENDING
    attempt_count: int = 0
    last_error: str = ""
    provider_ref: str = ""  # 업체 접수 번호 (솔라피 groupId)
    log_id: int | None = None

    def record_result(self, status: SMSDeliveryStatus, error: str = "") -> None:
        self.attempt_count += 1
        self.status = status
        self.last_error = error

    def can_retry(self) -> bool:
        return self.status == SMSDeliveryStatus.FAILED and self.attempt_count < MAX_ATTEMPTS


@dataclass
class SMSBatchResult:
    messages: list[SMSMessage] = field(default_factory=list)
    invalid: list[SMSRecipient] = field(default_factory=list)

    def count(self, status: SMSDeliveryStatus) -> int:
        return sum(1 for m in self.messages if m.status == status)

    @property
    def success(self) -> int:
        return self.count(SMSDeliveryStatus.SUCCESS)

    @property
    def failed(self) -> int:
        return self.count(SMSDeliveryStatus.FAILED) + len(self.invalid)

    @property
    def unknown(self) -> int:
        return self.count(SMSDeliveryStatus.UNKNOWN)

    @property
    def total(self) -> int:
        return len(self.messages) + len(self.invalid)

    def summary(self) -> str:
        if not self.total:
            return "보낼 문자가 없습니다."
        parts = [f"성공 {self.success}건"]
        if self.failed:
            parts.append(f"실패 {self.failed}건")
        if self.unknown:
            parts.append(f"결과 확인 필요 {self.unknown}건")
        return "출발 확정 문자: " + ", ".join(parts)


# ---------------------------------------------------------------- 외부 문자 서비스 클라이언트

class SMSSendError(Exception):
    """발송 실패가 명확함 (재시도 가능)."""


class SMSUnknownResult(Exception):
    """보냈는지 알 수 없음 (재시도하지 않음)."""


class SMSRejected(SMSSendError):
    """보내기 전에 거부됨 (허용 목록 밖 번호 등). 다시 보내도 같으므로 재시도하지 않음."""


class ConsoleSMSClient:
    """개발용: 실제로 보내지 않고 로그에 남긴다. 항상 성공."""

    sent: list[tuple[str, str]] = []  # 테스트 확인용

    def send_sms(self, contact: str, message: str) -> None:
        logger.info("[SMS 가짜 발송] to=%s msg=%s", contact, message)
        ConsoleSMSClient.sent.append((contact, message))
        return None  # 실제 클라이언트는 업체 접수 번호를 돌려줄 수 있다 (선택)


class FlakyDemoSMSClient(ConsoleSMSClient):
    """시연용: 번호마다 처음 2번은 실패하고 3번째에 성공한다 (재시도 동작 확인용).

    .env 에 SMS_CLIENT=apps.sms.services.FlakyDemoSMSClient 로 설정한다.
    """

    _attempts: dict[str, int] = {}

    def send_sms(self, contact: str, message: str) -> None:
        n = FlakyDemoSMSClient._attempts.get(contact, 0) + 1
        FlakyDemoSMSClient._attempts[contact] = n
        if n % 3 != 0:
            raise SMSSendError(f"시연용 실패 ({n}번째 시도)")
        super().send_sms(contact, message)


def get_client():
    path = getattr(settings, "SMS_CLIENT", "apps.sms.services.ConsoleSMSClient")
    return import_string(path)()


# ---------------------------------------------------------------- 발송

def _attempt_fits(started: float, limit: float, call_timeout: float, clock) -> bool:
    """남은 시간 안에 외부 호출 1회(최대 call_timeout 초)를 끝낼 수 있는지."""
    return clock() - started + call_timeout <= limit


def _deliver(client, msg: SMSMessage, started: float, limit: float, clock, product_id=None) -> None:
    """최초 1회 + 명확한 실패 시 재시도(최대 4회). 시간 한도를 넘길 시도는 하지 않는다. msg 를 갱신한다."""
    call_timeout = getattr(client, "timeout", 0)  # 외부 호출 1회의 최대 대기 시간 (가짜 클라이언트는 0)
    calls = 0
    r = msg.recipient
    while True:
        if not _attempt_fits(started, limit, call_timeout, clock):
            if calls == 0:  # 이번에 한 번도 못 보냄: 보내지 않았음이 확실하므로 실패
                msg.status = SMSDeliveryStatus.FAILED
                msg.last_error = f"발송 시간 한도({limit}초) 초과로 보내지 않음"
                logger.warning("문자 발송 시간 한도 초과로 보내지 않음 product=%s member=%s", product_id, r.member_id)
            break  # 재시도 중이면 마지막 실패 상태를 그대로 둔다
        rejected = False
        calls += 1
        try:
            ref = client.send_sms(r.contact, msg.message)
            msg.record_result(SMSDeliveryStatus.SUCCESS)
            msg.provider_ref = str(ref or "")[:100]
        except SMSUnknownResult as e:
            msg.record_result(SMSDeliveryStatus.UNKNOWN, str(e))
        except Exception as e:  # SMSSendError 및 연결 오류 등 명확한 실패
            msg.record_result(SMSDeliveryStatus.FAILED, str(e))
            rejected = isinstance(e, SMSRejected)  # 보내기 전 거부: 다시 해도 같으므로 재시도 안 함
        if msg.status != SMSDeliveryStatus.SUCCESS:
            logger.warning("문자 발송 %s product=%s member=%s attempt=%s error=%s", msg.status.value,
                           product_id, r.member_id, msg.attempt_count, msg.last_error)
        if rejected or msg.status != SMSDeliveryStatus.FAILED or calls >= MAX_ATTEMPTS:
            break


# ---------------------------------------------------------------- 발송 기록 (저장 실패해도 발송에는 영향 없음)

def _log_start(msg: SMSMessage, notice: DepartureNotice) -> None:
    r = msg.recipient
    try:
        with transaction.atomic():  # 저장점: 실패해도 호출자 트랜잭션(신청 저장)은 계속된다
            log = SMSLog.objects.create(
                member_id=r.member_id, recipient_name=(r.name or "")[:50], contact=(r.contact or "")[:20],
                product_id=notice.product_id, product_name=(notice.product_name or "")[:100],
                message=msg.message, status=msg.status.value, last_error=msg.last_error,
            )
        msg.log_id = log.pk
    except Exception:
        logger.exception("문자 발송 기록 저장 실패 member=%s", r.member_id)


def _log_finish(msg: SMSMessage, **extra) -> None:
    if msg.log_id is None:
        return
    try:
        SMSLog.objects.filter(pk=msg.log_id).update(
            status=msg.status.value, attempt_count=msg.attempt_count, last_error=msg.last_error,
            provider_ref=msg.provider_ref, updated_at=timezone.now(), **extra)
    except Exception:
        logger.exception("문자 발송 기록 갱신 실패 log=%s", msg.log_id)


# ---------------------------------------------------------------- 발송

@dataclass
class DeparturePlan:
    """보내기 전에 발송 기록을 만들어 둔 출발 확정 문자 묶음."""

    notice: DepartureNotice
    messages: list[SMSMessage] = field(default_factory=list)
    invalid: list[SMSRecipient] = field(default_factory=list)


def prepare_departure_confirmation(recipients: list[SMSRecipient], notice: DepartureNotice) -> DeparturePlan:
    """수신자마다 발송 기록을 '발송 중'(정보 누락은 '정보 누락')으로 먼저 만든다. 외부 호출은 하지 않는다.

    신청 저장과 같은 트랜잭션에서 부르면 신청과 기록이 함께 저장된다. 그래서 저장 직후 보내기 전에
    서버가 멈춰도 기록이 남고, 직원 문자 기록 화면에 '발송 확인 필요'로 나타나 다시 보낼 수 있다 (설계 05장 §2.2).
    """
    plan = DeparturePlan(notice)
    for r in recipients:
        if not r.contact or not notice.product_name or not notice.departure_date:
            logger.warning("문자 대상 정보 누락 product=%s member=%s", notice.product_id, r.member_id)
            plan.invalid.append(r)
            text = notice.message(r) if notice.departure_date else ""
            _log_start(SMSMessage(r, text, SMSDeliveryStatus.INVALID, last_error="연락처 또는 상품 정보 누락"), notice)
            continue
        msg = SMSMessage(r, notice.message(r))
        _log_start(msg, notice)
        plan.messages.append(msg)
    return plan


def deliver_departure_confirmation(plan: DeparturePlan, client=None, clock=time.monotonic) -> SMSBatchResult:
    """준비한 묶음을 실제로 보내고 기록을 결과로 바꾼다 (재시도 규칙·시간 한도 적용)."""
    client = client or get_client()
    notice = plan.notice
    result = SMSBatchResult(invalid=list(plan.invalid))
    limit = getattr(settings, "SMS_BATCH_TIME_LIMIT", 40)
    started = clock()
    for msg in plan.messages:
        if msg.log_id is None:  # 준비 때 기록을 못 만들었으면 지금 다시 시도
            _log_start(msg, notice)
        _deliver(client, msg, started, limit, clock, notice.product_id)
        _log_finish(msg)
        result.messages.append(msg)
    logger.info("문자 발송 결과 product=%s %s", notice.product_id, result.summary())
    return result


def send_departure_confirmation(recipients: list[SMSRecipient], notice: DepartureNotice, client=None,
                                clock=time.monotonic) -> SMSBatchResult:
    """기록 준비와 발송을 한 번에 (트랜잭션 밖에서 바로 보낼 때)."""
    return deliver_departure_confirmation(prepare_departure_confirmation(recipients, notice), client, clock)


# ---------------------------------------------------------------- 직원 조회·재발송

class SMSResendError(Exception):
    pass


def load_logs(problems_only: bool = False):
    """발송 기록 (최근 순). problems_only 면 실패·결과 불명·멈춘 발송 중만."""
    qs = SMSLog.objects.select_related("resent_by")
    if problems_only:
        stale = timezone.now() - timedelta(minutes=STALE_PENDING_MINUTES)
        qs = qs.filter(Q(status__in=[SMSLog.Status.FAILED, SMSLog.Status.UNKNOWN])
                       | Q(status=SMSLog.Status.PENDING, updated_at__lt=stale))
    return qs


def resend_logged_sms(log_id: int, employee_id: int, confirm_duplicate: bool = False, client=None,
                      clock=time.monotonic) -> SMSLog:
    """실패·결과 불명 기록을 같은 문구로 다시 보낸다 (재시도 규칙·시간 한도 동일).

    결과 불명은 이미 받았을 수 있어 confirm_duplicate 가 있어야 보낸다.
    두 직원이 동시에 눌러도 한 번만 보내도록 잠근 상태에서 '발송 중'으로 바꾼 뒤 보낸다.
    """
    with transaction.atomic():
        log = SMSLog.objects.select_for_update().filter(pk=log_id).first()
        if log is None:
            raise SMSResendError("문자 기록을 찾을 수 없습니다.")
        if not log.needs_attention:
            raise SMSResendError(f"'{log.get_status_display()}' 상태라 다시 보낼 수 없습니다.")
        if not log.contact:
            raise SMSResendError("연락처가 없어 보낼 수 없습니다.")
        if log.may_be_delivered and not confirm_duplicate:
            raise SMSResendError("이미 받았을 수 있는 문자입니다. 중복 수신 가능성을 확인한 뒤 다시 보내 주세요.")
        log.status = SMSLog.Status.PENDING
        log.save(update_fields=["status", "updated_at"])

    msg = SMSMessage(SMSRecipient(log.member_id, log.recipient_name, log.contact), log.message,
                     attempt_count=log.attempt_count, log_id=log.pk)
    _deliver(client or get_client(), msg, clock(), getattr(settings, "SMS_BATCH_TIME_LIMIT", 40), clock,
             log.product_id)
    _log_finish(msg, resend_count=log.resend_count + 1, resent_by_id=employee_id)
    log.refresh_from_db()
    logger.info("문자 재발송 log=%s status=%s employee=%s", log.pk, log.status, employee_id)
    return log
