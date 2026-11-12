"""Travel Management 서비스 (설계 05장 TravelManager).

뷰는 권한 확인과 입력 형식 검사만 하고, 업무 처리는 이 모듈의 함수를 호출한다.
"""

import logging
from datetime import date, timedelta

from django.db import transaction
from django.db.models import F, Q
from django.utils import timezone

from apps.souvenir import services as souvenir_services

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

HISTORY_ID_PREFIX = "application-"  # 신청에서 만든 이력의 completed_travel_id (신청마다 1건)


def trip_end_date(product: TravelProduct) -> date:
    """여행 마지막 날 (출발일이 1일째)."""
    return product.departure_date + timedelta(days=max(product.travel_days, 1) - 1)


def complete_finished_trips(member_id: int | None = None, today: date | None = None) -> int:
    """다녀온 여행을 완료 이력으로 만든다. 만든 이력 수를 돌려준다.

    대상: 취소하지 않았고 출발이 확정된 신청(허니문은 신청 상태, 그 외는 상품 상태) 중 여행 마지막 날이 지난 것.
    출발이 확정되지 않은 채 출발일이 지난 상품은 여행이 진행되지 않았으므로 이력이 되지 않는다.
    이력은 신청 고객 계정에 1건, 가격은 실제 신청 금액(할인 반영). 여러 번 호출해도 중복되지 않는다.
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
    done_ids = set(done.values_list("completed_travel_id", flat=True))
    created = 0
    for app in apps:
        key = f"{HISTORY_ID_PREFIX}{app.pk}"
        end = trip_end_date(app.product)
        if key in done_ids or end >= today:
            continue
        _, made = TravelHistory.objects.get_or_create(
            member_id=app.member_id, completed_travel_id=key,
            defaults=dict(product_name=app.product.name, tour_level=app.product.tour_level,
                          travel_days=app.product.travel_days, price=app.final_amount,
                          completed=True, completed_at=end),
        )
        created += made
    if created:
        logger.info("완료 여행 이력 %s건 생성 member=%s", created, member_id if member_id is not None else "전체")
    return created


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
    """테마·등급으로 자동 결정되는 기본 구성 (등록·수정 화면 확인용)."""
    if theme not in Theme.values or level not in TourLevel.values:
        raise ValueError("유효하지 않은 테마 또는 등급입니다.")
    t, lv = THEME_DEFAULTS[Theme(theme)], LEVEL_DEFAULTS[TourLevel(level)]
    souvenirs = souvenir_services.souvenir_names_for_theme(theme)
    return {
        "theme_service": t["service"],
        "souvenir": ", ".join(souvenirs) if souvenirs else "(등록된 기념품 없음)",
        "transport": TransportOption(t["transport"]).label,
        "hotel_level": HotelLevel(lv["hotel"]).label,
        "meal": MealOption(lv["meal"]).label,
    }


# ---------------------------------------------------------------- 직원 상품 관리 (UC6, UC7)

def _fill(product: TravelProduct, data: dict) -> None:
    for field in PRODUCT_INPUT_FIELDS:
        if field in data:
            setattr(product, field, data[field])
    product.departure_status = None  # 신청이 없는 상품만 저장되므로 출발 상태는 처음부터 다시 결정
    product.apply_default_configuration()
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
