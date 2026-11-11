"""가격·단골 할인 계산 (설계 05장 TravelPriceCalculator).

할인 전 전체 금액 = (1인 기본 가격 + 옵션 추가금) × 실제 여행자 수
  옵션 추가금은 기본보다 높이면 +, 낮추면 − (차액 반영). 1인 가격은 기본 가격의 50% 아래로 내려가지 않는다.
단골 할인액     = 단골이면 할인 전 금액의 10% (원 미만 버림), 아니면 0
최종 신청 금액   = 할인 전 금액 − 단골 할인액
"""

from dataclasses import dataclass
from datetime import date
from typing import Iterable

from .choices import INCLUDED_DRINKS, REGULAR_DISCOUNT_PERCENT, REGULAR_MIN_TRIPS

# 1인당 옵션 금액(원). 설계상 금액은 후속 확정 → 임시 금액.
# 기본 구성에서 바꾼 항목만 (선택 금액 − 기본 금액)을 반영한다. 높이면 추가, 낮추면 차액만큼 차감.
# 예) 프리미엄(5등급) 상품에서 4등급 호텔로 바꾸면 1인 −200,000원
OPTION_PRICE_TABLE: dict[str, dict[str, int]] = {
    "hotel_level": {"STAR3": 0, "STAR4": 150_000, "STAR5": 350_000},
    "transport": {"LUXURY_VAN_10": 0, "LUXURY_CAR_2": 0, "LIMOUSINE": 150_000},
    "meal": {"LUNCHBOX": 0, "LOCAL": 80_000, "FINE": 200_000},
}
MIN_UNIT_PRICE_RATIO = 0.5  # 옵션을 아무리 낮춰도 1인 가격은 기본 가격의 50% 이상 (임시 정책)

# 추가 음료 1인 금액(원). 식사에 기본 포함된 음료는 0원 (기본 제공분 중복 과금 방지)
DRINK_PRICES: dict[str, int] = {"WINE": 50_000, "BEER": 20_000, "CHAMPAGNE": 60_000, "COFFEE": 10_000}


@dataclass(frozen=True)
class PriceBreakdown:
    unit_price: int
    traveler_count: int
    subtotal: int
    is_regular: bool
    discount_amount: int
    final_amount: int


def option_difference(product, field: str, chosen: str) -> int:
    """기본 구성 대비 한 항목의 1인 금액 차이. 높이면 +, 낮추면 −, 같으면 0."""
    return OPTION_PRICE_TABLE[field].get(chosen, 0) - OPTION_PRICE_TABLE[field].get(getattr(product, field), 0)


def option_surcharge(product, config: dict) -> int:
    """기본 구성과 다른 항목의 차액 합계 + 추가 음료 (기본 제공 음료는 중복 과금하지 않음)."""
    total = sum(option_difference(product, f, config[f]) for f in ("hotel_level", "transport", "meal"))
    included = INCLUDED_DRINKS.get(config["meal"], set())
    total += sum(DRINK_PRICES.get(d, 0) for d in config.get("drinks", []) if d not in included)
    return total


def calculate_unit_price(product, config: dict) -> int:
    """1인 가격 = 1인 기본 가격 + 옵션 추가금(음수 가능). config: hotel_level·transport·meal·drinks."""
    floor = int(product.base_price * MIN_UNIT_PRICE_RATIO)
    return max(product.base_price + option_surcharge(product, config), floor)


def is_regular_customer(histories: Iterable, application_date: date) -> bool:
    """최근 3년 이내 완료 여행이 3회 이상인지. 같은 완료 여행 식별값은 한 번만 센다."""
    trips = {h.completed_travel_id for h in histories if h.is_completed_within_three_years(application_date)}
    return len(trips) >= REGULAR_MIN_TRIPS


def calculate_application_price(
    unit_price: int, traveler_count: int, histories: Iterable, application_date: date
) -> PriceBreakdown:
    subtotal = unit_price * traveler_count
    regular = is_regular_customer(histories, application_date)
    discount = subtotal * REGULAR_DISCOUNT_PERCENT // 100 if regular else 0
    return PriceBreakdown(unit_price, traveler_count, subtotal, regular, discount, subtotal - discount)
