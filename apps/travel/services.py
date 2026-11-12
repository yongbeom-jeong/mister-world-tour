"""Travel Management 서비스 (설계 05장 TravelManager).

뷰는 권한 확인과 입력 형식 검사만 하고, 업무 처리는 이 모듈의 함수를 호출한다.
"""

import logging
from datetime import date, timedelta

from django.core.exceptions import ValidationError
from django.db import transaction
from django.db.models import Count, F, Q
from django.utils import timezone

from apps.souvenir import services as souvenir_services

from . import configuration as cfg
from . import images
from .choices import (
    LEVEL_DEFAULTS,
    THEME_DEFAULTS,
    DepartureStatus,
    HotelLevel,
    MealOption,
    Theme,
    TourLevel,
    TransportOption,
)
from .models import TravelApplication, TravelHistory, TravelProduct

logger = logging.getLogger(__name__)

# 등록·수정 시 직원이 입력하는 필드 (기본 구성·출발 상태는 시스템이 결정)
PRODUCT_INPUT_FIELDS = (
    "name", "destination", "description", "theme", "tour_level", "base_price", "travel_days", "departure_date",
)
# 직원이 테마·등급 기본값을 확인한 뒤 바꿀 수 있는 기본 구성 (비우면 기본값, UC6 (3))
BASE_CONFIG_FIELDS = ("hotel_level", "meal", "transport")


class ProductNotFound(Exception):
    pass


class ProductHasApplications(Exception):
    """신청이 한 건이라도 있는 상품은 수정·삭제할 수 없다."""


# ---------------------------------------------------------------- 조회

def view_previous_travel_history(member_id: int) -> list[TravelHistory]:
    """UC3 이전 여행 이력: 완료일 최근 순, 완료일 없는 기록은 뒤로. 끝난 여행을 먼저 이력에 반영한다."""
    complete_finished_trips(member_id)
    return list(
        TravelHistory.objects.filter(member_id=member_id).order_by(F("completed_at").desc(nulls_last=True), "-id")
    )


# ---------------------------------------------------------------- 완료 여행 이력

HISTORY_ID_PREFIX = "product-"  # 신청에서 만든 이력의 completed_travel_id (고객·상품마다 1건)


def trip_end_date(product: TravelProduct) -> date:
    """여행 마지막 날 (출발일이 1일째)."""
    return product.departure_date + timedelta(days=max(product.travel_days, 1) - 1)


def complete_finished_trips(member_id: int | None = None, today: date | None = None) -> int:
    """다녀온 여행을 완료 이력으로 만든다. 만든 이력 수를 돌려준다.

    대상: 취소하지 않았고 출발이 확정된 신청(허니문은 신청 상태, 그 외는 상품 상태) 중 여행 마지막 날이 지난 것.
    출발이 확정되지 않은 채 출발일이 지난 상품은 여행이 진행되지 않았으므로 이력이 되지 않는다.
    완료 여행은 신청 건수가 아니라 실제 다녀온 여행으로 센다 (설계 05·07장): 같은 고객이 같은 상품에
    신청을 여러 번 했어도 이력은 1건이고, 가격은 그 신청 금액(할인 반영)의 합계다.
    여러 번 호출해도 중복되지 않는다.
    """
    today = today or timezone.localdate()
    confirmed = (Q(product__theme=Theme.HONEYMOON, departure_status=DepartureStatus.CONFIRMED)
                 | (~Q(product__theme=Theme.HONEYMOON) & Q(product__departure_status=DepartureStatus.CONFIRMED)))
    apps = (TravelApplication.objects.filter(confirmed, cancelled_at__isnull=True, product__departure_date__lt=today)
            .select_related("product"))
    done = TravelHistory.objects.filter(completed_travel_id__startswith=HISTORY_ID_PREFIX)
    if member_id is not None:
        apps = apps.filter(member_id=member_id)
        done = done.filter(member_id=member_id)
    done_keys = set(done.values_list("member_id", "completed_travel_id"))

    trips: dict[tuple[int, str], dict] = {}  # (고객, 식별값) → 다녀온 여행 1건
    for app in apps:
        product = app.product
        key = (app.member_id, f"{HISTORY_ID_PREFIX}{product.pk}")
        end = trip_end_date(product)
        if key in done_keys or end >= today:
            continue
        trip = trips.setdefault(key, dict(product_name=product.name, tour_level=product.tour_level,
                                          travel_days=product.travel_days, price=0, completed=True, completed_at=end))
        trip["price"] += app.final_amount

    created = 0
    for (mid, travel_id), fields in trips.items():
        _, made = TravelHistory.objects.get_or_create(member_id=mid, completed_travel_id=travel_id, defaults=fields)
        created += made
    if created:
        logger.info("완료 여행 이력 %s건 생성 member=%s", created, member_id if member_id is not None else "전체")
    return created


def staff_dashboard_counts() -> dict:
    """직원 홈 통계: 상품 수, 취소 제외 신청 수, 출발 확정 상품 수."""
    return {
        "products": TravelProduct.objects.count(),
        "applications": TravelApplication.objects.filter(cancelled_at__isnull=True).count(),
        "confirmed": TravelProduct.objects.filter(departure_status=DepartureStatus.CONFIRMED).count(),
    }


def recent_products(limit: int = 5) -> list[TravelProduct]:
    """직원 홈: 최근 등록 상품과 신청 수(n_apps)."""
    return list(TravelProduct.objects.annotate(n_apps=Count("applications")).order_by("-created_at")[:limit])


def load_products(theme: str | None = None, level: str | None = None) -> list[TravelProduct]:
    """상품 목록. 조건을 생략하면 전체 (직원 관리 화면)."""
    qs = TravelProduct.objects.all()
    if theme:
        qs = qs.filter(theme=theme)
    if level:
        qs = qs.filter(tour_level=level)
    return list(qs.order_by("departure_date", "id"))


def select_product(product_id: int) -> TravelProduct:
    try:
        return TravelProduct.objects.get(pk=product_id)
    except TravelProduct.DoesNotExist as e:
        raise ProductNotFound(product_id) from e


def has_applications(product_id: int) -> bool:
    return TravelProduct.objects.filter(pk=product_id, applications__isnull=False).exists()


def load_default_configuration(theme: str, level: str) -> dict:
    """테마·등급의 기본 구성과 직원이 고를 수 있는 값 (등록·수정 화면용).

    hotel_level·meal·transport 는 기본값 이름, choices 는 [값, 이름] 목록(첫 항목이 기본값).
    """
    if theme not in Theme.values or level not in TourLevel.values:
        raise ValueError("유효하지 않은 테마 또는 등급입니다.")
    t, lv = THEME_DEFAULTS[Theme(theme)], LEVEL_DEFAULTS[TourLevel(level)]
    souvenirs = souvenir_services.souvenir_names_for_theme(theme)
    labels = {"hotel_level": HotelLevel, "meal": MealOption, "transport": TransportOption}
    allowed = cfg.base_option_choices(theme, level)
    return {
        "theme_service": t["service"],
        "souvenir": ", ".join(souvenirs) if souvenirs else "(등록된 기념품 없음)",
        "transport": TransportOption(t["transport"]).label,
        "hotel_level": HotelLevel(lv["hotel"]).label,
        "meal": MealOption(lv["meal"]).label,
        "choices": {k: [[v, labels[k](v).label] for v in allowed[k]] for k in BASE_CONFIG_FIELDS},
    }


# ---------------------------------------------------------------- 직원 상품 관리 (UC6, UC7)

def _fill(product: TravelProduct, data: dict) -> None:
    for field in PRODUCT_INPUT_FIELDS:
        if field in data:
            setattr(product, field, data[field])
    product.departure_status = None  # 신청이 없는 상품만 저장되므로 출발 상태는 처음부터 다시 결정
    product.apply_default_configuration()
    # 직원이 고른 기본 구성 (비어 있으면 테마·등급 기본값 그대로)
    errors = cfg.base_configuration_errors(product.theme, product.tour_level, data)
    if errors:
        raise ValidationError(errors)
    for field in BASE_CONFIG_FIELDS:
        if data.get(field):
            setattr(product, field, data[field])
    product.full_clean()


def _apply_image(product: TravelProduct, image) -> None:
    """image: None=그대로, False=삭제(테마 기본 사진 사용), 업로드 파일=교체. 실패 시 images.InvalidImage."""
    if image is None:
        return
    if image is False:
        product.image = ""
        return
    product.image.save("upload.jpg", images.process_upload(image), save=False)


def register_tour_product(data: dict, image=None) -> TravelProduct:
    """UC6 상품 등록. 기본 구성·기념품·출발 상태는 시스템이 결정한다."""
    product = TravelProduct()
    _fill(product, data)
    _apply_image(product, image)
    product.save()
    return product


def find_registered_since(data: dict, since) -> TravelProduct | None:
    """since 이후 같은 입력(이름·테마·출발일)으로 저장된 상품. 등록 중 오류 뒤 저장 여부 확인용."""
    return (TravelProduct.objects.filter(name=data.get("name"), theme=data.get("theme"),
                                         departure_date=data.get("departure_date"), created_at__gte=since)
            .order_by("-id").first())


def modify_tour_product(product_id: int, data: dict, image=None) -> TravelProduct:
    """UC7 상품 수정. 같은 상품을 잠근 상태에서 신청 존재 여부를 확인한 뒤 변경한다."""
    with transaction.atomic():
        product = _lock(product_id)
        if product.applications.exists():
            raise ProductHasApplications(product_id)
        _fill(product, data)
        _apply_image(product, image)
        product.save()
    return product


def delete_tour_product(product_id: int) -> str:
    """UC7 상품 삭제. 삭제한 상품 이름을 반환한다."""
    with transaction.atomic():
        product = _lock(product_id)
        if product.applications.exists():
            raise ProductHasApplications(product_id)
        name = product.name
        product.delete()
    return name


def _lock(product_id: int) -> TravelProduct:
    try:
        return TravelProduct.objects.select_for_update().get(pk=product_id)
    except TravelProduct.DoesNotExist as e:
        raise ProductNotFound(product_id) from e
