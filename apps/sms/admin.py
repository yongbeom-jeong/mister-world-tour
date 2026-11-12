from django.contrib import admin

from .models import SMSLog


@admin.register(SMSLog)
class SMSLogAdmin(admin.ModelAdmin):
    list_display = ("created_at", "recipient_name", "contact", "product_name", "status", "attempt_count", "resend_count")
    list_filter = ("status",)
    search_fields = ("recipient_name", "contact", "product_name", "provider_ref")
    readonly_fields = [f.name for f in SMSLog._meta.fields]
