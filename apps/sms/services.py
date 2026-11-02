"""SMS Notification 서비스 (설계 09장 SMSNotificationManager · SMSServiceClient · SMSMessage).

- 수신 대상 선정과 출발 확정 판정은 Travel Management 가 한다. 여기서는 받은 대상에게 보내기만 한다.
- 최초 발송 1회 + 명확한 실패 시 최대 3회 재시도 (전체 최대 4회 외부 호출).
- 결과 불명(응답 시간 초과 등)은 실패로 바꿔 즉시 재발송하지 않는다 (중복 문자 방지).
- 실제 문자 업체는 미정 → settings.SMS_CLIENT 로 교체한다. 기본은 콘솔(로그)로 보내는 가짜 클라이언트.
"""

import logging
from dataclasses import dataclass, field
from enum import Enum

from django.conf import settings
from django.utils.module_loading import import_string

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
        return (f"[미스터 월드 투어] {recipient.name}님, '{self.product_name}' 여행이 "
                f"{self.departure_date:%Y-%m-%d} 출발로 확정되었습니다. 즐거운 여행 되세요!")


@dataclass
class SMSMessage:
    recipient: SMSRecipient
    message: str
    status: SMSDeliveryStatus = SMSDeliveryStatus.PENDING
    attempt_count: int = 0
    last_error: str = ""

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


class ConsoleSMSClient:
    """개발용: 실제로 보내지 않고 로그에 남긴다. 항상 성공."""

    sent: list[tuple[str, str]] = []  # 테스트 확인용

    def send_sms(self, contact: str, message: str) -> None:
        logger.info("[SMS 가짜 발송] to=%s msg=%s", contact, message)
        ConsoleSMSClient.sent.append((contact, message))


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

def send_departure_confirmation(recipients: list[SMSRecipient], notice: DepartureNotice, client=None) -> SMSBatchResult:
    client = client or get_client()
    result = SMSBatchResult()
    for r in recipients:
        if not r.contact or not notice.product_name or not notice.departure_date:
            logger.warning("문자 대상 정보 누락 product=%s member=%s", notice.product_id, r.member_id)
            result.invalid.append(r)
            continue
        msg = SMSMessage(r, notice.message(r))
        while True:
            try:
                client.send_sms(r.contact, msg.message)
                msg.record_result(SMSDeliveryStatus.SUCCESS)
            except SMSUnknownResult as e:
                msg.record_result(SMSDeliveryStatus.UNKNOWN, str(e))
            except Exception as e:  # SMSSendError 및 연결 오류 등 명확한 실패
                msg.record_result(SMSDeliveryStatus.FAILED, str(e))
            if msg.status != SMSDeliveryStatus.SUCCESS:
                logger.warning("문자 발송 %s product=%s member=%s attempt=%s error=%s", msg.status.value,
                               notice.product_id, r.member_id, msg.attempt_count, msg.last_error)
            if not msg.can_retry():
                break
        result.messages.append(msg)
    logger.info("문자 발송 결과 product=%s %s", notice.product_id, result.summary())
    return result
