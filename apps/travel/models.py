"""여행 관리 모델 (설계 05장 Travel Management).

금액은 원 단위 정수로 저장한다. 옵션 추가금 표와 반올림 방식은 설계상 후속 확정 항목이다.
"""

from datetime import date

from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models

from .choices import (
    ALLOWED_LEVELS_FOR_RESTRICTED,
    GROUP_CAPACITY,
    GROUP_MIN_DEPARTURE,
    HONEYMOON_PARTY_SIZE,
    LEVEL_DEFAULTS,
    REGULAR_YEARS,
    RESTRICTED_LEVEL_THEMES,
    THEME_DEFAULTS,
    DepartureStatus,
    HotelLevel,
    MealOption,
    Theme,
    TourLevel,
    TransportOption,
)


class ConfigurationFields(models.Model):
    """호텔·교통·식사·음료 구성 (설계 05장 TravelConfiguration).

    상품의 기본 구성과 신청의 변경 구성이 같은 필드를 쓰도록 추상 모델로 둔다.
    """

    hotel_level = models.CharField("호텔", max_length=10, choices=HotelLevel.choices)
    transport = models.CharField("교통", max_length=20, choices=TransportOption.choices)
    meal = models.CharField("식사", max_length=10, choices=MealOption.choices)
    extra_drinks = models.CharField("추가 음료", max_length=200, blank=True)  # 음료 종류·수량 단위는 후속 확정

    class Meta:
        abstract = True


class TravelProduct(ConfigurationFields):
    """직원이 등록한 여행 상품과 기본 구성, 공동 출발 상태."""

    name = models.CharField("상품 이름", max_length=100)
    # 여행지·소개: 설계에 없는 항목. 화면 표시용으로 임의 추가
    destination = models.CharField("여행지", max_length=100, blank=True)
    description = models.TextField("상품 소개", blank=True)
    theme = models.CharField("테마", max_length=10, choices=Theme.choices)
    tour_level = models.CharField("초기 등급", max_length=10, choices=TourLevel.choices)
    base_price = models.PositiveIntegerField("1인 기본 가격(원)")
    travel_days = models.PositiveSmallIntegerField("여행 기간(일)")  # 기간 표현 방식은 후속 확정
    departure_date = models.DateField("출발일")
    theme_service = models.CharField("기본 테마 서비스", max_length=100)
    souvenir = models.ForeignKey(
        "souvenir.Souvenir", on_delete=models.PROTECT, null=True, blank=True,
        related_name="products", verbose_name="기본 기념품",
    )
    # 허니문 외 테마의 상품 공동 출발 상태. 허니문은 None 이고 신청별 상태를 쓴다.
    departure_status = models.CharField(
        "출발 상태", max_length=10, choices=DepartureStatus.choices, null=True, blank=True
    )
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        verbose_name = "여행 상품"
        verbose_name_plural = "여행 상품"
        ordering = ["departure_date", "id"]

    def __str__(self) -> str:
        return f"[{self.get_theme_display()}/{self.get_tour_level_display()}] {self.name}"

    @property
    def is_honeymoon(self) -> bool:
        return self.theme == Theme.HONEYMOON

    @property
    def capacity(self) -> int:
        return HONEYMOON_PARTY_SIZE if self.is_honeymoon else GROUP_CAPACITY

    def apply_default_configuration(self) -> None:
        """테마·등급으로 기본 구성을 자동 결정한다. 등록·수정 시 호출."""
        self.theme_service = THEME_DEFAULTS[self.theme]["service"]
        self.transport = THEME_DEFAULTS[self.theme]["transport"]
        self.hotel_level = LEVEL_DEFAULTS[self.tour_level]["hotel"]
        self.meal = LEVEL_DEFAULTS[self.tour_level]["meal"]
        if self.departure_status is None and not self.is_honeymoon:
            self.departure_status = DepartureStatus.PENDING
        if self.is_honeymoon:
            self.departure_status = None

    def clean(self) -> None:
        if self.theme in RESTRICTED_LEVEL_THEMES and self.tour_level not in ALLOWED_LEVELS_FOR_RESTRICTED:
            raise ValidationError({"tour_level": "허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만 가능합니다."})

    def validate_application_capacity(self, current_count: int, requested_count: int) -> str | None:
        """정원 검사. 문제가 있으면 안내 문구, 없으면 None."""
        if self.is_honeymoon:
            if requested_count != HONEYMOON_PARTY_SIZE:
                return "허니문 상품은 신청 고객 1명과 동반 1명, 정확히 2명이어야 합니다."
            return None
        if requested_count < 1:
            return "여행자를 1명 이상 입력해 주세요."
        if current_count + requested_count > GROUP_CAPACITY:
            return f"정원 {GROUP_CAPACITY}명을 넘습니다. (현재 {current_count}명, 남은 자리 {GROUP_CAPACITY - current_count}명)"
        return None

    def update_departure_status(self, total_travelers: int) -> str | None:
        """허니문 외 상품: 실제 여행자 3명 이상이면 확정. 확정 후에는 유지한다."""
        if self.is_honeymoon:
            return None
        if self.departure_status != DepartureStatus.CONFIRMED and total_travelers >= GROUP_MIN_DEPARTURE:
            self.departure_status = DepartureStatus.CONFIRMED
        return self.departure_status


class TravelApplication(ConfigurationFields):
    """고객의 여행 신청 (설계 05장 TravelApplication)."""

    member = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="applications", verbose_name="신청 고객"
    )
    product = models.ForeignKey(TravelProduct, on_delete=models.PROTECT, related_name="applications", verbose_name="상품")
    unit_price = models.PositiveIntegerField("1인 가격(원)")
    subtotal = models.PositiveIntegerField("할인 전 금액(원)")
    discount_amount = models.PositiveIntegerField("단골 할인액(원)", default=0)
    final_amount = models.PositiveIntegerField("최종 금액(원)")
    request_key = models.CharField("요청 키", max_length=64, unique=True)
    submitted_at = models.DateTimeField("신청 시점", auto_now_add=True)
    # 허니문 신청의 독립 출발 상태. 다른 테마는 None 이고 상품 공동 상태를 쓴다.
    departure_status = models.CharField(
        "출발 상태", max_length=10, choices=DepartureStatus.choices, null=True, blank=True
    )

    class Meta:
        verbose_name = "여행 신청"
        verbose_name_plural = "여행 신청"
        ordering = ["-submitted_at"]

    def __str__(self) -> str:
        return f"#{self.pk} {self.member} → {self.product.name}"

    @property
    def traveler_count(self) -> int:
        return self.travelers.count()

    def current_departure_status(self) -> str | None:
        return self.departure_status if self.product.is_honeymoon else self.product.departure_status


class Traveler(models.Model):
    """실제 여행자 한 명. 신청 고객 본인도 여기에 포함한다. 상세 항목은 후속 확정."""

    application = models.ForeignKey(TravelApplication, on_delete=models.CASCADE, related_name="travelers")
    name = models.CharField("성명", max_length=50)
    contact = models.CharField("연락처", max_length=20, blank=True)
    is_applicant = models.BooleanField("신청 고객 본인", default=False)

    class Meta:
        verbose_name = "여행자"
        verbose_name_plural = "여행자"

    def __str__(self) -> str:
        return self.name


class TravelHistory(models.Model):
    """완료된 여행 이력. 여행 당시 상품 정보를 복사해 보관한다 (설계 05장 TravelHistory)."""

    member = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="travel_histories", verbose_name="고객"
    )
    completed_travel_id = models.CharField("완료 여행 식별값", max_length=64)
    product_name = models.CharField("상품 이름", max_length=100)
    tour_level = models.CharField("등급", max_length=10, choices=TourLevel.choices)
    travel_days = models.PositiveSmallIntegerField("여행 기간(일)")
    price = models.PositiveIntegerField("가격(원)")
    completed = models.BooleanField("완료 여부", default=False)
    completed_at = models.DateField("완료일", null=True, blank=True)

    class Meta:
        verbose_name = "여행 이력"
        verbose_name_plural = "여행 이력"
        ordering = ["-completed_at", "-id"]
        constraints = [
            models.UniqueConstraint(fields=["member", "completed_travel_id"], name="unique_member_completed_travel")
        ]

    def __str__(self) -> str:
        return f"{self.member} - {self.product_name}"

    def is_completed_within_three_years(self, application_date: date) -> bool:
        if not self.completed or self.completed_at is None or self.completed_at > application_date:
            return False
        try:
            cutoff = application_date.replace(year=application_date.year - REGULAR_YEARS)
        except ValueError:  # 2월 29일
            cutoff = application_date.replace(year=application_date.year - REGULAR_YEARS, day=28)
        return self.completed_at >= cutoff
