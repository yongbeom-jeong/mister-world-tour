from django.conf import settings
from django.db import models

from apps.travel.choices import Theme


class Souvenir(models.Model):
    """미리 등록된 기념품 품목과 현재 재고 (설계 06장 Souvenir)."""

    name = models.CharField("품목 이름", max_length=50, unique=True)
    theme = models.CharField("대응 테마", max_length=10, choices=Theme.choices)
    stock_quantity = models.PositiveIntegerField("현재 재고", default=0)

    class Meta:
        verbose_name = "기념품"
        verbose_name_plural = "기념품"
        ordering = ["theme", "name"]

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
