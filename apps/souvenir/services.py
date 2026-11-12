"""Souvenir Management 서비스 (설계 06장 SouvenirManager).

입고는 준비(prepare) → 등록(record) 두 단계다.
- 준비: 입력을 검사하고 요청 키와 최초 요청 시점을 정해 사용자별 서버 문맥(세션)에 보관한다. 저장하지 않는다.
- 등록: 같은 키로 이미 저장된 입고가 있으면 그 결과를 돌려주고 재고를 다시 늘리지 않는다.
  없으면 준비한 내용과 일치하는지 확인하고, 입고 내역 저장과 재고 증가를 한 트랜잭션으로 반영한다.

품목 등록·수정·삭제(직원), 차감 내역 조회, 출발 확정 시 재고 차감(issue_for_departure)도 여기서 한다.
삭제한 품목 중 입고·차감 내역이 있는 것은 숨김(is_active=False) 처리해 내역을 보존한다.
"""

import uuid
from dataclasses import dataclass
from enum import Enum

from django.db import IntegrityError, transaction
from django.db.models import F
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from apps.travel.choices import Theme

from .models import Souvenir, StockIssue, StockReceipt

MAX_QUANTITY = 10_000  # 한 번에 입고할 수 있는 최대 수량 (임시 정책: 오입력 방지)
MAX_PENDING_KEYS = 20  # 세션에 보관할 준비 문맥 수 (여러 탭 대비, 오래된 것부터 정리)


class StockReceiptStatus(str, Enum):
    SUCCESS = "SUCCESS"
    INVALID_INPUT = "INVALID_INPUT"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    SAVE_FAILED = "SAVE_FAILED"


@dataclass
class StockReceiptResponse:
    status: StockReceiptStatus
    message: str
    receipt: StockReceipt | None = None
    current_souvenir: Souvenir | None = None
    stock_refresh_succeeded: bool = False
    already_recorded: bool = False  # 같은 키의 재시도로 기존 결과를 돌려준 경우


class StockReceiptError(Exception):
    def __init__(self, status: StockReceiptStatus, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


# ---------------------------------------------------------------- 조회

def active_souvenirs():
    return Souvenir.objects.filter(is_active=True)


def load_souvenirs() -> list[Souvenir]:
    return list(active_souvenirs().order_by("theme", "name"))


def get_stock_quantity(souvenir_id: int) -> int | None:
    """현재 재고. 품목이 없으면 None (재고 0과 구분)."""
    return active_souvenirs().filter(pk=souvenir_id).values_list("stock_quantity", flat=True).first()


# ---------------------------------------------------------------- 입고 (UC8)

def _validate_quantity(quantity) -> int:
    text = str(quantity).strip() if quantity is not None else ""
    if not (text.isascii() and text.isdigit()):  # 빈 값, 소수, 음수, 문자 거부
        raise StockReceiptError(StockReceiptStatus.INVALID_INPUT, "입고 수량은 1 이상의 정수로 입력해 주세요.")
    q = int(text)
    if q < 1:
        raise StockReceiptError(StockReceiptStatus.INVALID_INPUT, "입고 수량은 1개 이상이어야 합니다.")
    if q > MAX_QUANTITY:
        raise StockReceiptError(StockReceiptStatus.INVALID_INPUT, f"한 번에 {MAX_QUANTITY:,}개까지 입고할 수 있습니다.")
    return q


def _find_souvenir(souvenir_id) -> Souvenir:
    try:
        return active_souvenirs().get(pk=int(souvenir_id))
    except (TypeError, ValueError, Souvenir.DoesNotExist):
        raise StockReceiptError(StockReceiptStatus.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.")


def prepare_stock_receipt(pending: dict, employee_id: int, souvenir_id, quantity) -> dict:
    """입고 준비. pending 은 사용자 세션의 준비 문맥 저장소(dict)이며 이 함수가 갱신한다."""
    q = _validate_quantity(quantity)
    souvenir = _find_souvenir(souvenir_id)
    key = uuid.uuid4().hex
    pending[key] = {
        "employee_id": employee_id, "souvenir_id": souvenir.pk, "quantity": q,
        "received_at": timezone.now().isoformat(),
    }
    while len(pending) > MAX_PENDING_KEYS:
        pending.pop(next(iter(pending)))
    return {"request_key": key, "souvenir_id": souvenir.pk, "souvenir_name": souvenir.name, "quantity": q}


def record_souvenir_stock_receipt(pending: dict, employee_id: int, souvenir_id, quantity, request_key: str
                                  ) -> StockReceiptResponse:
    try:
        q = _validate_quantity(quantity)
        sid = int(souvenir_id)
    except (TypeError, ValueError):
        return StockReceiptResponse(StockReceiptStatus.INVALID_INPUT, "입고 품목이 올바르지 않습니다.")
    except StockReceiptError as e:
        return StockReceiptResponse(e.status, e.message)
    if not request_key:
        return StockReceiptResponse(StockReceiptStatus.INVALID_INPUT, "입고 요청 키가 없습니다. 다시 시도해 주세요.")

    # 1) 같은 키로 이미 저장된 입고가 있는가 (재시도)
    existing = StockReceipt.objects.filter(request_key=request_key).first()
    if existing:
        return _existing_result(existing, employee_id, sid, q, pending, request_key)

    # 2) 준비한 내용과 일치하는가
    ctx = pending.get(request_key)
    if not ctx or (ctx["employee_id"], ctx["souvenir_id"], ctx["quantity"]) != (employee_id, sid, q):
        return StockReceiptResponse(StockReceiptStatus.CONFLICT,
                                    "준비한 입고 내용과 다릅니다. 품목과 수량을 확인한 뒤 다시 입고해 주세요.")

    # 3) 입고 내역 저장 + 재고 증가를 한 트랜잭션으로
    try:
        with transaction.atomic():
            updated = active_souvenirs().filter(pk=sid).update(stock_quantity=F("stock_quantity") + q)
            if not updated:
                return StockReceiptResponse(StockReceiptStatus.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.")
            stock_after = Souvenir.objects.values_list("stock_quantity", flat=True).get(pk=sid)
            receipt = StockReceipt.objects.create(
                souvenir_id=sid, employee_id=employee_id, quantity=q,
                received_at=parse_datetime(ctx["received_at"]), request_key=request_key, stock_after=stock_after,
            )
    except IntegrityError:
        # 동시에 같은 키가 저장된 경우: 위 트랜잭션은 취소됐고, 먼저 저장된 결과를 확인한다
        existing = StockReceipt.objects.filter(request_key=request_key).first()
        if existing:
            return _existing_result(existing, employee_id, sid, q, pending, request_key)
        return StockReceiptResponse(StockReceiptStatus.SAVE_FAILED, "입고를 저장하지 못했습니다. 다시 시도해 주세요.")

    pending.pop(request_key, None)  # 확인된 입고는 제출 문맥 종료
    return _success(receipt, "입고가 완료되었습니다.")


def _existing_result(existing: StockReceipt, employee_id, sid, q, pending, key) -> StockReceiptResponse:
    if not existing.matches_request(employee_id, sid, q):
        return StockReceiptResponse(StockReceiptStatus.CONFLICT, "이미 다른 내용으로 처리된 입고 요청입니다.")
    pending.pop(key, None)
    res = _success(existing, "이미 처리된 입고입니다. 재고를 다시 늘리지 않았습니다.")
    res.already_recorded = True
    return res


def _success(receipt: StockReceipt, message: str) -> StockReceiptResponse:
    res = StockReceiptResponse(StockReceiptStatus.SUCCESS, message, receipt=receipt)
    try:
        res.current_souvenir = Souvenir.objects.get(pk=receipt.souvenir_id)
        res.stock_refresh_succeeded = True
    except Exception:  # 입고는 성공했지만 현재 재고 재조회 실패 → 성공은 유지
        res.message += " 현재 재고는 다시 조회해 주세요."
    return res


# ---------------------------------------------------------------- 품목 등록

MAX_NAME = 50


class SouvenirRegistrationError(Exception):
    def __init__(self, errors: dict):
        super().__init__(str(errors))
        self.errors = errors


def _validate_input(name, theme, exclude_id: int | None = None) -> str:
    name = (name or "").strip()
    errors = {}
    if not name:
        errors["name"] = "품목 이름을 입력해 주세요."
    elif len(name) > MAX_NAME:
        errors["name"] = f"품목 이름은 {MAX_NAME}자 이내로 입력해 주세요."
    else:
        same = Souvenir.objects.filter(name__iexact=name).exclude(pk=exclude_id).first()
        if same:
            errors["name"] = ("이미 등록된 품목 이름입니다." if same.is_active
                              else "삭제한 품목 중 같은 이름이 있습니다 (내역 보존용). 다른 이름을 써 주세요.")
    if theme not in Theme.values:
        errors["theme"] = "연결할 테마를 선택해 주세요."
    if errors:
        raise SouvenirRegistrationError(errors)
    return name


def register_souvenir(name, theme) -> Souvenir:
    """새 기념품 품목을 테마에 연결해 재고 0개로 등록한다. 수량은 입고(UC8)로 늘린다.

    등록 즉시 그 테마 상품의 신청 검사·출발 확정 차감 대상이 된다 (테마 품목마다 여행자 1인당 1개).
    """
    name = _validate_input(name, theme)
    try:
        return Souvenir.objects.create(name=name, theme=theme)
    except IntegrityError:  # 동시에 같은 이름이 등록된 경우
        raise SouvenirRegistrationError({"name": "이미 등록된 품목 이름입니다."})


# ---------------------------------------------------------------- 품목 수정·삭제 (직원)

class SouvenirNotFound(Exception):
    pass


def get_souvenir(souvenir_id: int) -> Souvenir:
    s = active_souvenirs().filter(pk=souvenir_id).first()
    if s is None:
        raise SouvenirNotFound(souvenir_id)
    return s


def theme_has_souvenir(theme: str) -> bool:
    return active_souvenirs().filter(theme=theme).exists()


def update_souvenir(souvenir_id: int, name, theme) -> Souvenir:
    """이름·연결 테마 수정. 재고는 입고와 출발 확정 차감으로만 바뀐다.

    테마를 바꾸면 이후 출발 확정부터 새 테마 상품에서 차감된다 (이미 차감한 내역은 그대로).
    """
    with transaction.atomic():
        s = active_souvenirs().select_for_update().filter(pk=souvenir_id).first()
        if s is None:
            raise SouvenirNotFound(souvenir_id)
        s.name = _validate_input(name, theme, exclude_id=s.pk)
        s.theme = theme
        try:
            with transaction.atomic():
                s.save(update_fields=["name", "theme"])
        except IntegrityError:
            raise SouvenirRegistrationError({"name": "이미 등록된 품목 이름입니다."})
    return s


def delete_souvenir(souvenir_id: int) -> bool:
    """품목 삭제. 내역이 없으면 실제로 지우고 True, 입고·차감 내역이 있으면 숨기고(내역 보존) False."""
    with transaction.atomic():
        s = active_souvenirs().select_for_update().filter(pk=souvenir_id).first()
        if s is None:
            raise SouvenirNotFound(souvenir_id)
        if s.receipts.exists() or s.issues.exists():
            s.is_active = False
            s.save(update_fields=["is_active"])
            return False
        s.delete()
        return True


# ---------------------------------------------------------------- 차감 내역 조회 (직원)

def load_issues(souvenir_id: int | None = None):
    """출발 확정 차감 내역 (최근 순). 신청·상품·고객은 표시용으로 함께 읽는다."""
    qs = StockIssue.objects.select_related("souvenir", "application__product", "application__member")
    if souvenir_id:
        qs = qs.filter(souvenir_id=souvenir_id)
    return qs.order_by("-issued_at", "-id")


# ---------------------------------------------------------------- 테마 기념품 조회·차감 (Travel Management 가 호출)

def souvenirs_for_theme(theme: str) -> list[Souvenir]:
    return list(active_souvenirs().filter(theme=theme).order_by("id"))


def souvenir_names_for_theme(theme: str) -> list[str]:
    return list(active_souvenirs().filter(theme=theme).order_by("id").values_list("name", flat=True))


class SouvenirShortage(Exception):
    def __init__(self, name: str, needed: int, stock: int):
        super().__init__(f"{name}: needed={needed} stock={stock}")
        self.name = name
        self.needed = needed
        self.stock = stock


def issue_for_departure(theme: str, items: list[tuple[int, int]]) -> int:
    """출발 확정된 신청들의 테마 기념품을 차감한다. items: [(신청 id, 여행자 수)].

    테마에 연결된 품목마다 여행자 1인당 1개. 호출자의 트랜잭션 안에서 실행하며,
    한 품목이라도 모자라면 SouvenirShortage 를 낸다 (호출자 트랜잭션이 취소되어 앞의 차감도 되돌아감).
    (품목, 신청)마다 한 번만 차감하고, 재고 감소는 '재고 >= 필요 수량' 조건부 UPDATE 라 음수가 되지 않는다.
    반환값은 차감한 총 개수 (모든 품목 합계).
    """
    app_ids = [a for a, _ in items]
    total = 0
    for souvenir in souvenirs_for_theme(theme):
        done = set(StockIssue.objects.filter(souvenir=souvenir, application_id__in=app_ids)
                   .values_list("application_id", flat=True))
        todo = [(a, n) for a, n in items if a not in done and n > 0]
        needed = sum(n for _, n in todo)
        if not needed:
            continue
        updated = Souvenir.objects.filter(pk=souvenir.pk, stock_quantity__gte=needed).update(
            stock_quantity=F("stock_quantity") - needed)
        stock = Souvenir.objects.values_list("stock_quantity", flat=True).get(pk=souvenir.pk)
        if not updated:
            raise SouvenirShortage(souvenir.name, needed, stock)
        remaining = stock + needed
        for application_id, n in todo:
            remaining -= n
            StockIssue.objects.create(souvenir=souvenir, application_id=application_id, quantity=n,
                                      stock_after=remaining)
        total += needed
    return total
