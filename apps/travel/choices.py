"""여행 관련 선택값과 테마·등급별 기본 구성표 (설계 01장 5.1, 05장 4).

다른 앱(souvenir 등)도 import 하므로 이 파일은 models 를 import 하지 않는다.
"""

from django.db import models


class Theme(models.TextChoices):
    HONEYMOON = "HONEYMOON", "허니문 낭만 투어"
    FILIAL = "FILIAL", "효도 힐링 투어"
    GOLF = "GOLF", "골프 챌린지 투어"
    TREKKING = "TREKKING", "아웃도어 트레킹 투어"


class TourLevel(models.TextChoices):
    CLASSIC = "CLASSIC", "클래식"
    GRAND = "GRAND", "그랜드"
    PREMIUM = "PREMIUM", "프리미엄"


class HotelLevel(models.TextChoices):
    STAR3 = "STAR3", "3등급 호텔"
    STAR4 = "STAR4", "4등급 호텔"
    STAR5 = "STAR5", "5등급 호텔"


class MealOption(models.TextChoices):
    LUNCHBOX = "LUNCHBOX", "도시락"
    LOCAL = "LOCAL", "현지식 레스토랑 식사"
    FINE = "FINE", "고급 레스토랑 스테이크·샴페인"


class TransportOption(models.TextChoices):
    LUXURY_CAR_2 = "LUXURY_CAR_2", "2인 전용 고급차량"
    LUXURY_VAN_10 = "LUXURY_VAN_10", "10인 승합 고급차량"
    LIMOUSINE = "LIMOUSINE", "고급차량 + 공항 리무진 픽업"  # 임시 옵션


class DrinkOption(models.TextChoices):
    """식사에 추가하는 음료 (임시 옵션: 설계상 종류·수량 단위 미정, 1인 1세트 기준)."""

    WINE = "WINE", "와인 페어링"
    BEER = "BEER", "수제맥주 세트"
    CHAMPAGNE = "CHAMPAGNE", "샴페인"
    COFFEE = "COFFEE", "스페셜티 커피"  # 과제 원문 "샴페인, 커피 추가"


class DepartureStatus(models.TextChoices):
    PENDING = "PENDING", "미확정"
    CONFIRMED = "CONFIRMED", "출발 확정"


# 테마별 기본 서비스·교통 (01장 5.1)
THEME_DEFAULTS = {
    Theme.HONEYMOON: {"service": "2인 전용 로맨틱 룸 장식", "transport": TransportOption.LUXURY_CAR_2},
    Theme.FILIAL: {"service": "안마·지압 서비스", "transport": TransportOption.LUXURY_VAN_10},
    Theme.GOLF: {"service": "골프 리조트 테마 연출", "transport": TransportOption.LUXURY_VAN_10},
    Theme.TREKKING: {"service": "트레킹·산악 테마 연출", "transport": TransportOption.LUXURY_VAN_10},
}

# 등급별 기본 호텔·식사 (01장 5.1)
LEVEL_DEFAULTS = {
    TourLevel.CLASSIC: {"hotel": HotelLevel.STAR3, "meal": MealOption.LUNCHBOX},
    TourLevel.GRAND: {"hotel": HotelLevel.STAR4, "meal": MealOption.LOCAL},
    TourLevel.PREMIUM: {"hotel": HotelLevel.STAR5, "meal": MealOption.FINE},
}

# 테마별로 고를 수 있는 교통 (임시 정책: 허니문은 2인 차량, 나머지는 10인 승합 차량 기반)
ALLOWED_TRANSPORTS = {
    Theme.HONEYMOON: {TransportOption.LUXURY_CAR_2, TransportOption.LIMOUSINE},
    Theme.FILIAL: {TransportOption.LUXURY_VAN_10, TransportOption.LIMOUSINE},
    Theme.GOLF: {TransportOption.LUXURY_VAN_10, TransportOption.LIMOUSINE},
    Theme.TREKKING: {TransportOption.LUXURY_VAN_10, TransportOption.LIMOUSINE},
}

# 음료 규칙 (임시 정책): 도시락에는 음료 추가 불가, 고급 식사에는 샴페인이 기본 포함
NO_DRINK_MEALS = {MealOption.LUNCHBOX}
INCLUDED_DRINKS = {MealOption.FINE: {DrinkOption.CHAMPAGNE}}

# 허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만 허용
RESTRICTED_LEVEL_THEMES = {Theme.HONEYMOON, Theme.FILIAL}
ALLOWED_LEVELS_FOR_RESTRICTED = {TourLevel.GRAND, TourLevel.PREMIUM}

# 낮은 것부터 높은 순서 (등급 비교용)
HOTEL_ORDER = [HotelLevel.STAR3, HotelLevel.STAR4, HotelLevel.STAR5]
MEAL_ORDER = [MealOption.LUNCHBOX, MealOption.LOCAL, MealOption.FINE]
# 허니문·효도는 그랜드 등급 이상만 (계획서 1.다): 고객 옵션도 그랜드 기본(4등급 호텔·현지식) 아래로는 고를 수 없다
RESTRICTED_MIN_HOTEL = HotelLevel.STAR4
RESTRICTED_MIN_MEAL = MealOption.LOCAL

# 정원·출발 규칙
HONEYMOON_PARTY_SIZE = 2      # 허니문: 신청마다 정확히 2명
GROUP_CAPACITY = 10           # 그 외: 상품 전체 정원
GROUP_MIN_DEPARTURE = 3       # 그 외: 실제 여행자 합계 3명 이상이면 출발 확정

# 단골 할인
REGULAR_MIN_TRIPS = 3         # 최근 3년 내 완료 여행 3회 이상
REGULAR_YEARS = 3
REGULAR_DISCOUNT_PERCENT = 10
