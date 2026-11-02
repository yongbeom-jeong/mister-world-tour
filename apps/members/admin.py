from django.contrib import admin
from django.contrib.auth.admin import UserAdmin

from .models import Member


@admin.register(Member)
class MemberAdmin(UserAdmin):
    list_display = ("username", "name", "role", "contact", "is_active")
    list_filter = ("role", "is_active")
    fieldsets = UserAdmin.fieldsets + (("회원 정보", {"fields": ("name", "address", "contact", "role")}),)
    add_fieldsets = UserAdmin.add_fieldsets + (("회원 정보", {"fields": ("name", "address", "contact", "role")}),)
