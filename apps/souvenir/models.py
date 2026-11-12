from django.conf import settings
from django.db import models
from django.db.models.functions import Lower

from apps.travel.choices import Theme


class Souvenir(models.Model):
    """기념품 품목과 현재 재고 (설계 06장 Souvenir). 테마에 연결되며 한 테마에 여러 품목이 있을 수 있다."""

    name = models.CharField("품목 이름", max_length=50, unique=True)
    theme = models.CharField("대응 테마", max_length=10, choices=Theme.choices)
    stock_quantity = models.PositiveIntegerField("현재 재고", default=0)
    # 입고·차감 내역이 있는 품목을 삭제하면 내역 보존을 위해 숨긴다. 숨긴 품목은 신청 검사·차감·입고에서 빠진다.
    is_active = models.BooleanField("사용 중", default=True)

    class Meta:
        verbose_name = "기념품"
        verbose_name_plural = "기념품"
        ordering = ["theme", "name"]
        # 품목 이름은 대소문자를 무시하고 중복 금지 (숨긴 품목 포함). 동시 등록도 DB 에서 막는다.
        constraints = [models.UniqueConstraint(Lower("name"), name="unique_souvenir_name_ci")]

    def __str__(self) -> str:
        return f"{self.name} ({self.get_theme_display()})"


class StockReceipt(models.Model):
    """한 번의 기념품 입고 내역 (설계 06장 StockReceipt).

    request_key 로 같은 입고의 재시도를 구별해 재고가 두 번 늘지 않게 한다.
    """

    souvenir = models.ForeignKey(Souvenir, on_delete=models.PROTECT, related_name="receipts", verbose_name="품목")
    employee = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="stock_receipts", verbose_name="입고 직원"
    )
    quantity = models.PositiveIntegerField("입고 수량")
    received_at = models.DateTimeField("입고 요청 시점")
    request_key = models.CharField("요청 키", max_length=64, unique=True)
    stock_after = models.PositiveIntegerField("입고 직후 재고")

    class Meta:
        verbose_name = "기념품 입고"
        verbose_name_plural = "기념품 입고"
        ordering = ["-received_at"]
        constraints = [models.CheckConstraint(condition=models.Q(quantity__gte=1), name="stock_receipt_quantity_gte_1")]

    def __str__(self) -> str:
        return f"{self.souvenir.name} +{self.quantity}"

    def matches_request(self, employee_id: int, souvenir_id: int, quantity: int) -> bool:
        return (self.employee_id, self.souvenir_id, self.quantity) == (employee_id, souvenir_id, quantity)


class StockIssue(models.Model):
    """출발 확정으로 차감한 기념품 (품목·신청마다 1행, 여행자 1인당 1개).

    허니문은 신청이 확정될 때, 다른 테마는 상품이 확정될 때(그때까지의 신청 전부)와
    확정 뒤 추가 신청마다 차감한다. 테마에 연결된 품목이 여러 개면 품목마다 차감한다.
    (품목, 신청)마다 한 번만 기록해 두 번 차감되지 않게 한다.
    """

    souvenir = models.ForeignKey(Souvenir, on_delete=models.PROTECT, related_name="issues", verbose_name="품목")
    application = models.ForeignKey(
        "travel.TravelApplication", on_delete=models.PROTECT, related_name="souvenir_issues", verbose_name="여행 신청"
    )
    quantity = models.PositiveIntegerField("차감 수량")
    issued_at = models.DateTimeField("차감 시점", auto_now_add=True)
    stock_after = models.PositiveIntegerField("차감 직후 재고")

    class Meta:
        verbose_name = "기념품 차감"
        verbose_name_plural = "기념품 차감"
        ordering = ["-issued_at"]
        constraints = [models.UniqueConstraint(fields=["souvenir", "application"], name="unique_issue_per_application")]

    def __str__(self) -> str:
        return f"{self.souvenir.name} -{self.quantity}"
