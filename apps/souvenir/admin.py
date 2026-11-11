from django.contrib import admin

from .models import Souvenir, StockIssue, StockReceipt


@admin.register(Souvenir)
class SouvenirAdmin(admin.ModelAdmin):
    list_display = ("name", "theme", "stock_quantity")


@admin.register(StockReceipt)
class StockReceiptAdmin(admin.ModelAdmin):
    list_display = ("souvenir", "quantity", "stock_after", "employee", "received_at")
    readonly_fields = ("request_key",)


@admin.register(StockIssue)
class StockIssueAdmin(admin.ModelAdmin):
    list_display = ("souvenir", "quantity", "stock_after", "application", "issued_at")
