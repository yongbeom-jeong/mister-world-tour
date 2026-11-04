from django.contrib import admin

from .models import Traveler, TravelApplication, TravelHistory, TravelProduct


@admin.register(TravelProduct)
class TravelProductAdmin(admin.ModelAdmin):
    list_display = ("name", "theme", "tour_level", "base_price", "departure_date", "departure_status")
    list_filter = ("theme", "tour_level", "departure_status")
    search_fields = ("name",)


class TravelerInline(admin.TabularInline):
    model = Traveler
    extra = 0


@admin.register(TravelApplication)
class TravelApplicationAdmin(admin.ModelAdmin):
    list_display = ("id", "member", "product", "final_amount", "departure_status", "submitted_at")
    list_filter = ("product__theme",)
    inlines = [TravelerInline]


@admin.register(TravelHistory)
class TravelHistoryAdmin(admin.ModelAdmin):
    list_display = ("member", "product_name", "tour_level", "price", "completed", "completed_at")
    list_filter = ("completed",)
