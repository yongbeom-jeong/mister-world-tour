"""여행 상품 선택·구성(UC4)과 신청(UC5) 업무 처리 (설계 05장 TravelManager 2.1~2.2).

흐름
1. configure_travel_product : 현재 구성 + 변경 항목 → 새 구성과 1인 가격. 실패하면 직전 구성 유지.
2. review_application      : 여행자·정원·기념품 재고·단골 할인을 검사하고 금액을 계산한다. 저장하지 않는다.
                             성공하면 요청 키와 검토 내용을 돌려주며, 뷰가 사용자 세션에 보관한다.
3. submit_travel_application: 상품을 잠근 트랜잭션 안에서 모든 조건을 다시 검사해 저장하고 출발 상태를 판정한다.
                             출발이 확정된 신청의 테마 기념품(품목마다 여행자 1인당 1개)을 같은 트랜잭션에서 차감한다.
                             저장이 끝난 뒤 문자 대상에게 출발 확정 문자를 요청한다.
"""

import logging
import uuid
from dataclasses import dataclass, field

from django.db import transaction
from django.db.models import Count
from django.utils import timezone

from apps.members import services as member_services
from apps.sms import services as sms_service
from apps.souvenir import services as souvenir_services

from . import configuration as cfg
from . import services as travel_services
from .choices import GROUP_CAPACITY, GROUP_MIN_DEPARTURE, HONEYMOON_PARTY_SIZE, DepartureStatus
from .models import Traveler, TravelApplication, TravelHistory, TravelProduct
from .pricing import PriceBreakdown, calculate_application_price, calculate_unit_price

MAX_NAME = 50
logger = logging.getLogger(__name__)


class BookingError(Exception):
    """신청할 수 없는 사유. errors 는 화면에 항목별로 보여줄 안내."""

    def __init__(self, message: str, errors: dict | None = None):
        super().__init__(message)
        self.message = message
        self.errors = errors or {}


# ---------------------------------------------------------------- 조회 (고객)

def bookable_products(theme: str | None, level: str | None) -> list[TravelProduct]:
    """고객 목록: 출발일이 지나지 않은 상품만."""
    qs = TravelProduct.objects.filter(departure_date__gt=timezone.localdate())
    if theme:
        qs = qs.filter(theme=theme)
    if level:
        qs = qs.filter(tour_level=level)
    return list(qs.order_by("departure_date", "id"))


def get_bookable_product(product_id: int) -> TravelProduct:
    product = TravelProduct.objects.filter(pk=product_id).first()
    if product is None:
        raise BookingError("상품을 찾을 수 없습니다. 삭제되었을 수 있습니다.")
    if product.departure_date <= timezone.localdate():
        raise BookingError("출발일이 지난 상품은 신청할 수 없습니다.")
    return product


def current_traveler_count(product_id: int) -> int:
    """같은 상품에 저장된 실제 여행자 수 (취소하지 않은 모든 신청 합계)."""
    return Traveler.objects.filter(application__product_id=product_id, application__cancelled_at__isnull=True).count()


def remaining_seats(product: TravelProduct) -> int:
    if product.is_honeymoon:
        return HONEYMOON_PARTY_SIZE
    return max(GROUP_CAPACITY - current_traveler_count(product.pk), 0)


# ---------------------------------------------------------------- UC4 구성 변경

def configure_travel_product(product: TravelProduct, current: dict, changes: dict) -> tuple[dict, int]:
    """바뀐 항목만 적용한 새 구성과 1인 가격. 실패 시 cfg.ConfigurationError (current 는 그대로)."""
    new = cfg.apply_changes(product, current, changes)
    return new, calculate_unit_price(product, new)


# ---------------------------------------------------------------- UC5 검토

@dataclass
class Review:
    request_key: str
    product_id: int
    member_id: int
    config: dict
    travelers: list[dict]
    breakdown: PriceBreakdown

    def to_session(self) -> dict:
        b = self.breakdown
        return {
            "product_id": self.product_id, "member_id": self.member_id, "config": self.config,
            "travelers": self.travelers,
            "breakdown": [b.unit_price, b.traveler_count, b.subtotal, b.is_regular, b.discount_amount, b.final_amount],
        }

    @classmethod
    def from_session(cls, key: str, data: dict) -> "Review":
        return cls(key, data["product_id"], data["member_id"], data["config"], data["travelers"],
                   PriceBreakdown(*data["breakdown"]))


def clean_travelers(product: TravelProduct, member, rows: list[dict]) -> list[dict]:
    """여행자 입력 검사. 첫 번째 행은 신청 고객 본인이다. 허니문은 정확히 2명."""
    errors = {}
    cleaned = []
    for i, row in enumerate(rows):
        name = (row.get("name") or "").strip()
        contact_raw = (row.get("contact") or "").strip()
        if i == 0:  # 신청 고객 본인: 계정 정보 사용
            name, contact_raw = member.name, member.contact
        if not name and not contact_raw:
            continue  # 비어 있는 행은 무시
        if not name:
            errors[f"traveler_{i}"] = f"{i + 1}번째 여행자의 성명을 입력해 주세요."
            continue
        if len(name) > MAX_NAME:
            errors[f"traveler_{i}"] = f"{i + 1}번째 여행자의 성명이 너무 깁니다."
            continue
        contact = ""
        if contact_raw:
            contact = member_services.normalize_contact(contact_raw)
            if contact is None:
                errors[f"traveler_{i}"] = f"{i + 1}번째 여행자의 연락처 형식이 올바르지 않습니다. 예: 010-1234-5678"
                continue
        cleaned.append({"name": name, "contact": contact, "is_applicant": i == 0})
    if errors:
        raise BookingError("여행자 정보를 확인해 주세요.", errors)
    if product.is_honeymoon and len(cleaned) != HONEYMOON_PARTY_SIZE:
        raise BookingError("허니문 상품은 신청 고객 1명과 동반 여행자 1명, 정확히 2명이어야 합니다.",
                           {"travelers": "동반 여행자 1명의 정보를 입력해 주세요."})
    if not cleaned:
        raise BookingError("여행자를 1명 이상 입력해 주세요.")
    return cleaned


def theme_souvenir_names(product: TravelProduct) -> list[str]:
    """상품 테마에 연결된 기념품 이름 (상세 화면 표시용)."""
    return souvenir_services.souvenir_names_for_theme(product.theme)


def souvenirs_needed(product: TravelProduct, traveler_count: int, current_count: int) -> int:
    """이 신청에 필요한 기념품 수.

    - 허니문, 이미 확정된 상품: 이 신청의 여행자 수 (확정과 함께 차감)
    - 이 신청으로 새로 확정되는 상품: 그때까지의 모든 신청 + 이 신청 (확정 순간 전부 차감)
    - 아직 확정 전: 이 신청의 여행자 수만큼 있는지만 확인 (차감은 확정 때)
    """
    if product.is_honeymoon or product.departure_status == DepartureStatus.CONFIRMED:
        return traveler_count
    if current_count + traveler_count >= GROUP_MIN_DEPARTURE:
        return current_count + traveler_count
    return traveler_count


def _souvenir_shortage_message(traveler_count: int, name: str, needed: int, stock: int) -> str:
    if needed > traveler_count:
        return (f"이 신청으로 출발이 확정되면 기념품 '{name}' {needed}개가 필요하지만 재고가 {stock}개뿐이라 "
                "지금은 신청을 받을 수 없습니다.")
    return f"기념품 '{name}' 재고가 부족해 지금은 {traveler_count}명 신청을 받을 수 없습니다. (남은 재고 {stock}개)"


def _check_business_rules(product: TravelProduct, traveler_count: int, current_count: int) -> None:
    """정원과 기념품 재고. 검토와 등록에서 같은 규칙을 쓴다."""
    problem = product.validate_application_capacity(current_count, traveler_count)
    if problem:
        raise BookingError(problem, {"travelers": problem})
    souvenirs = souvenir_services.souvenirs_for_theme(product.theme)
    if not souvenirs:
        raise BookingError("이 테마의 기념품 정보가 없어 신청할 수 없습니다. 고객센터에 문의해 주세요.")
    needed = souvenirs_needed(product, traveler_count, current_count)  # 테마 기념품마다 여행자 1인당 1개
    for s in souvenirs:
        if s.stock_quantity < needed:
            raise BookingError(_souvenir_shortage_message(traveler_count, s.name, needed, s.stock_quantity))


def _price(product: TravelProduct, member_id: int, config: dict, traveler_count: int) -> PriceBreakdown:
    travel_services.complete_finished_trips(member_id)  # 다녀온 여행을 이력에 반영한 뒤 단골 여부 판단
    histories = TravelHistory.objects.filter(member_id=member_id)
    unit = calculate_unit_price(product, config)
    return calculate_application_price(unit, traveler_count, histories, timezone.localdate())


def review_application(member, product_id: int, config: dict, traveler_rows: list[dict]) -> Review:
    product = get_bookable_product(product_id)
    errors = cfg.validate_configuration(product, config)
    if errors:
        raise BookingError("여행 구성을 다시 확인해 주세요.", errors)
    travelers = clean_travelers(product, member, traveler_rows)
    _check_business_rules(product, len(travelers), current_traveler_count(product.pk))
    breakdown = _price(product, member.pk, config, len(travelers))
    return Review(uuid.uuid4().hex, product.pk, member.pk, config, travelers, breakdown)


# ---------------------------------------------------------------- UC5 등록

@dataclass
class ApplicationResult:
    application: TravelApplication
    departure_status: str | None
    newly_confirmed: bool = False
    duplicate: bool = False
    souvenirs_issued: int = 0  # 이번 신청으로 차감한 기념품 수 (모든 품목 합계)
    sms: sms_service.SMSBatchResult | None = None
    recipients: list = field(default_factory=list)


class PriceChanged(BookingError):
    """검토 이후 금액이 바뀌어 다시 확인이 필요함."""


def _same_request(existing: TravelApplication, member, product: TravelProduct, review: Review) -> bool:
    """같은 요청 키로 저장된 신청이 이번 제출과 고객·상품·구성·여행자까지 같은지."""
    if existing.member_id != member.pk or existing.product_id != product.pk:
        return False
    saved_config = (existing.hotel_level, existing.transport, existing.meal,
                    cfg.normalize_drinks(existing.extra_drinks))
    config = review.config
    if saved_config != (config.get("hotel_level"), config.get("transport"), config.get("meal"),
                        cfg.normalize_drinks(config.get("drinks"))):
        return False
    saved_travelers = list(existing.travelers.order_by("pk").values_list("name", "contact", "is_applicant"))
    travelers = [(t["name"], t["contact"], t["is_applicant"]) for t in review.travelers]
    return saved_travelers == travelers


def submit_travel_application(member, review: Review) -> ApplicationResult:
    if review.member_id != member.pk:
        raise BookingError("다른 고객의 신청 내용입니다. 다시 검토해 주세요.")

    with transaction.atomic():
        # 같은 상품의 신청은 한 번에 하나씩 (정원 동시 초과 방지). SQLite 에서는 잠금이 무시된다.
        product = TravelProduct.objects.select_for_update().filter(pk=review.product_id).first()
        if product is None:
            raise BookingError("상품을 찾을 수 없습니다. 삭제되었을 수 있습니다.")

        existing = TravelApplication.objects.filter(request_key=review.request_key).first()
        if existing:  # 같은 확인 제출의 재시도 → 기존 결과
            if not _same_request(existing, member, product, review):
                raise BookingError("이미 다른 내용으로 처리된 신청 요청입니다.")
            return ApplicationResult(existing, existing.current_departure_status(), duplicate=True)

        if product.departure_date <= timezone.localdate():
            raise BookingError("출발일이 지난 상품은 신청할 수 없습니다.")
        errors = cfg.validate_configuration(product, review.config)
        if errors:
            raise BookingError("여행 구성을 다시 확인해 주세요.", errors)
        count = len(review.travelers)
        _check_business_rules(product, count, current_traveler_count(product.pk))
        breakdown = _price(product, member.pk, review.config, count)
        if breakdown != review.breakdown:
            raise PriceChanged(
                f"검토 이후 금액이 바뀌었습니다 ({review.breakdown.final_amount:,}원 → {breakdown.final_amount:,}원). "
                "다시 확인해 주세요."
            )

        app = TravelApplication.objects.create(
            member=member, product=product,
            hotel_level=review.config["hotel_level"], transport=review.config["transport"],
            meal=review.config["meal"], extra_drinks=",".join(review.config.get("drinks", [])),
            unit_price=breakdown.unit_price, subtotal=breakdown.subtotal,
            discount_amount=breakdown.discount_amount, final_amount=breakdown.final_amount,
            request_key=review.request_key,
            departure_status=DepartureStatus.CONFIRMED if product.is_honeymoon else None,
        )
        Traveler.objects.bulk_create([Traveler(application=app, **t) for t in review.travelers])

        newly_confirmed = False
        if product.is_honeymoon:
            recipient_ids = [member.pk]  # 허니문: 이 신청 고객만
            to_issue = [(app.pk, count)]
        else:
            was_confirmed = product.departure_status == DepartureStatus.CONFIRMED
            total = current_traveler_count(product.pk)
            product.update_departure_status(total)
            product.save(update_fields=["departure_status"])
            newly_confirmed = not was_confirmed and product.departure_status == DepartureStatus.CONFIRMED
            if newly_confirmed:  # 새로 확정: 이 상품의 모든 신청 고객 (취소 제외, 중복 제거)
                active = product.applications.filter(cancelled_at__isnull=True)
                recipient_ids = list(active.values_list("member_id", flat=True).distinct())
                to_issue = list(active.annotate(n=Count("travelers")).values_list("pk", "n"))
            elif was_confirmed:  # 이미 확정된 상품의 추가 신청: 새 신청 고객만
                recipient_ids = [member.pk]
                to_issue = [(app.pk, count)]
            else:
                recipient_ids = []
                to_issue = []

        # 출발 확정된 신청의 기념품 차감. 모자라면 신청 저장까지 모두 취소된다.
        try:
            issued = souvenir_services.issue_for_departure(product.theme, to_issue) if to_issue else 0
        except souvenir_services.SouvenirShortage as e:
            raise BookingError(_souvenir_shortage_message(count, e.name, e.needed, e.stock))

    # 저장이 끝난 뒤 문자 요청. 문자 실패는 신청·출발 상태를 되돌리지 않는다.
    result = ApplicationResult(app, app.current_departure_status(), newly_confirmed=newly_confirmed,
                               souvenirs_issued=issued)
    if recipient_ids:
        recipients = []
        for mid in sorted(set(recipient_ids)):
            info = member_services.get_contact(mid)
            if info:
                recipients.append(sms_service.SMSRecipient(mid, info[0], info[1]))
        notice = sms_service.DepartureNotice(product.pk, product.name, product.departure_date)
        try:
            result.sms = sms_service.send_departure_confirmation(recipients, notice)
        except Exception:  # 문자 모듈 자체 오류도 신청 성공을 바꾸지 않음
            logger.exception("문자 요청 실패 application=%s", app.pk)
            result.sms = sms_service.SMSBatchResult(invalid=recipients)
        result.recipients = recipients
    return result


# ---------------------------------------------------------------- 신청 취소

CANCEL_RULE = "출발이 확정되면 신청을 취소할 수 없어요."


def cancel_block_reason(app: TravelApplication, today=None) -> str | None:
    """취소할 수 없는 이유. 취소할 수 있으면 None. (화면 표시와 실제 취소에서 같은 규칙을 쓴다)"""
    today = today or timezone.localdate()
    if app.is_cancelled:
        return "이미 취소한 신청입니다."
    if app.current_departure_status() == DepartureStatus.CONFIRMED:
        return "출발이 확정된 신청은 취소할 수 없습니다."
    if app.product.departure_date <= today:
        return "출발일이 지난 신청은 취소할 수 없습니다."
    return None


def cancel_application(member, application_id: int) -> TravelApplication:
    """출발 확정 전 신청을 취소한다. 확정 판정과 겹치지 않게 상품을 잠근 뒤 다시 검사한다."""
    with transaction.atomic():
        product_id = (TravelApplication.objects.filter(pk=application_id, member=member)
                      .values_list("product_id", flat=True).first())
        if product_id is None:
            raise BookingError("신청을 찾을 수 없습니다.")
        TravelProduct.objects.select_for_update().filter(pk=product_id).first()  # 같은 상품의 신청·확정과 순서 맞춤
        app = TravelApplication.objects.select_for_update().select_related("product").get(pk=application_id)
        reason = cancel_block_reason(app)
        if reason:
            raise BookingError(reason)
        app.cancelled_at = timezone.now()
        app.save(update_fields=["cancelled_at"])
    logger.info("신청 취소 application=%s member=%s", app.pk, member.pk)
    return app


def find_submitted_application(member_id: int, request_key: str) -> TravelApplication | None:
    """같은 요청 키로 이미 저장된 이 고객의 신청 (재제출 안내용)."""
    if not request_key:
        return None
    return TravelApplication.objects.filter(member_id=member_id, request_key=request_key).first()


def my_applications(member_id: int) -> list[TravelApplication]:
    return list(
        TravelApplication.objects.filter(member_id=member_id).select_related("product")
        .annotate(n_travelers=Count("travelers")).order_by("-submitted_at")
    )

