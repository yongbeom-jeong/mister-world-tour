from django.contrib.auth.models import AbstractUser
from django.db import models
from django.db.models.functions import Lower


class Member(AbstractUser):
    """고객·직원 공통 회원 (설계 4장 Member).

    아이디(username)·비밀번호 해시(password)는 AbstractUser 가 제공한다.
    역할(role)로 고객과 직원을 구분한다.
    """

    class Role(models.TextChoices):
        CUSTOMER = "CUSTOMER", "고객"
        STAFF = "STAFF", "직원"

    name = models.CharField("성명", max_length=50)
    address = models.CharField("주소", max_length=200, blank=True)
    contact = models.CharField("연락처", max_length=20)
    role = models.CharField("역할", max_length=10, choices=Role.choices, default=Role.CUSTOMER)

    REQUIRED_FIELDS = ["name", "contact"]

    class Meta:
        verbose_name = "회원"
        verbose_name_plural = "회원"
        # 아이디는 대소문자를 무시하고 중복 금지. 폼 검사 이후 동시 가입도 DB 에서 막는다.
        constraints = [models.UniqueConstraint(Lower("username"), name="unique_member_username_ci")]

    def __str__(self) -> str:
        return f"{self.name}({self.username})"

    @property
    def is_customer(self) -> bool:
        return self.role == self.Role.CUSTOMER

    @property
    def is_staff_member(self) -> bool:
        """여행사 직원 여부. Django 관리자 권한(is_staff)과는 별개다."""
        return self.role == self.Role.STAFF
