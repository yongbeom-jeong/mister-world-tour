"""Souvenir Management 서비스 (설계 06장 SouvenirManager).

입고는 준비(prepare) → 등록(record) 두 단계다.
- 준비: 입력을 검사하고 요청 키와 최초 요청 시점을 정해 사용자별 서버 문맥(세션)에 보관한다. 저장하지 않는다.
- 등록: 같은 키로 이미 저장된 입고가 있으면 그 결과를 돌려주고 재고를 다시 늘리지 않는다.
  없으면 준비한 내용과 일치하는지 확인하고, 입고 내역 저장과 재고 증가를 한 트랜잭션으로 반영한다.
"""

import uuid
from dataclasses import dataclass
from enum import Enum

from django.db import IntegrityError, transaction
from django.db.models import F
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from .models import Souvenir, StockReceipt

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

def load_souvenirs() -> list[Souvenir]:
    return list(Souvenir.objects.order_by("theme", "name"))


def find_souvenir_for_theme(theme: str) -> Souvenir | None:
    """테마에 대응하는 등록 기념품. 여러 개면 가장 먼저 등록된 품목."""
    return Souvenir.objects.filter(theme=theme).order_by("id").first()


def get_stock_quantity(souvenir_id: int) -> int | None:
    """현재 재고. 품목이 없으면 None (재고 0과 구분)."""
    return Souvenir.objects.filter(pk=souvenir_id).values_list("stock_quantity", flat=True).first()


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
        return Souvenir.objects.get(pk=int(souvenir_id))
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
            updated = Souvenir.objects.filter(pk=sid).update(stock_quantity=F("stock_quantity") + q)
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
