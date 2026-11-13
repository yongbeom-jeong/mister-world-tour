from io import StringIO

from django.core.management import call_command
from django.test import TestCase, override_settings
from django.urls import reverse

from .models import Member

PW = "Travel-2026!x"


def signup_data(**kw):
    data = dict(member_type="CUSTOMER", username="hong_1", password1=PW, password2=PW,
                name="홍길동", address="서울시 종로구", contact="01012345678", staff_code="")
    data.update(kw)
    return data


class SignUpTests(TestCase):
    url = reverse("members:signup")

    def test_customer_signup_saves_hashed_password_and_normalized_contact(self):
        res = self.client.post(self.url, signup_data())
        self.assertRedirects(res, reverse("members:login"))
        m = Member.objects.get(username="hong_1")
        self.assertTrue(m.is_customer)
        self.assertEqual(m.contact, "010-1234-5678")
        self.assertNotEqual(m.password, PW)
        self.assertTrue(m.check_password(PW))

    @override_settings(STAFF_SIGNUP_CODE="CODE-1")
    def test_staff_signup_requires_code(self):
        res = self.client.post(self.url, signup_data(member_type="STAFF", staff_code="wrong"))
        self.assertEqual(res.status_code, 200)
        self.assertIn("staff_code", res.context["form"].errors)
        self.assertFalse(Member.objects.exists())

        self.client.post(self.url, signup_data(member_type="STAFF", staff_code="CODE-1"))
        self.assertTrue(Member.objects.get(username="hong_1").is_staff_member)

    def test_duplicate_username_case_insensitive(self):
        Member.objects.create_user(username="Hong_1", password=PW, name="a", contact="010")
        res = self.client.post(self.url, signup_data())
        self.assertIn("username", res.context["form"].errors)

    def test_invalid_inputs_point_to_fields(self):
        cases = {
            "username": signup_data(username="ab"),
            "contact": signup_data(contact="02-123-4567"),
            "password2": signup_data(password2="different"),
            "password1": signup_data(password1="1234", password2="1234"),
            "address": signup_data(address=""),
        }
        for field, data in cases.items():
            with self.subTest(field):
                res = self.client.post(self.url, data)
                self.assertIn(field, res.context["form"].errors)
        self.assertFalse(Member.objects.exists())


class LoginTests(TestCase):
    def setUp(self):
        self.customer = Member.objects.create_user(username="cust", password=PW, name="고객", contact="010")
        self.staff = Member.objects.create_user(username="emp", password=PW, name="직원", contact="010",
                                                role=Member.Role.STAFF)

    def login(self, username, password=PW):
        return self.client.post(reverse("members:login"), {"username": username, "password": password})

    def test_customer_login_opens_history_popup_once(self):
        self.assertRedirects(self.login("cust"), reverse("home"), fetch_redirect_response=False)
        first = self.client.get(reverse("home"))
        self.assertTrue(first.context["show_history_popup"])
        self.assertContains(first, "historyModal")
        self.assertFalse(self.client.get(reverse("home")).context["show_history_popup"])

    def test_popup_opens_on_page_reached_after_login(self):
        """로그인 전 보던 화면(next)으로 돌아가도 그 화면에서 이력 팝업을 연다 (UC2 (4))."""
        products = reverse("travel:products")
        res = self.client.post(reverse("members:login") + f"?next={products}",
                               {"username": "cust", "password": PW})
        self.assertRedirects(res, products, fetch_redirect_response=False)
        page = self.client.get(products)
        self.assertTrue(page.context["show_history_popup"])
        self.assertContains(page, "historyModal")
        self.assertContains(page, "openHistory();")
        self.assertFalse(self.client.get(reverse("home")).context["show_history_popup"])

    def test_staff_login_has_no_history_popup(self):
        self.login("emp")
        res = self.client.get(reverse("home"))
        self.assertFalse(res.context["show_history_popup"])
        self.assertContains(res, "직원 메뉴")
        self.assertNotContains(res, "historyModal")

    def test_wrong_password(self):
        res = self.login("cust", "wrong")
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, "아이디 또는 비밀번호가 올바르지 않습니다")
        self.assertFalse(res.wsgi_request.user.is_authenticated)

    def test_blank_login_asks_for_input(self):
        """빈 칸은 브라우저 말풍선이 아니라 화면 안내로 알린다 (폼은 novalidate)."""
        res = self.login("", "")
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, "아이디와 비밀번호를 모두 입력해 주세요")
        self.assertNotContains(res, "아이디 또는 비밀번호가 올바르지 않습니다")


class SeedDemoTests(TestCase):
    def test_seed_is_idempotent(self):
        call_command("seed_demo", stdout=StringIO())
        counts = Member.objects.count()
        call_command("seed_demo", stdout=StringIO())
        self.assertEqual(Member.objects.count(), counts)
        self.assertTrue(Member.objects.get(username="regular1").check_password("demo1234!"))



class CaseInsensitiveUsernameTests(TestCase):
    def test_db_rejects_same_username_with_different_case(self):
        """폼 검사를 지나 동시에 가입해도 DB 제약이 대소문자만 다른 아이디를 막는다."""
        from django.db import IntegrityError, transaction

        from . import services
        Member.objects.create_user(username="Hong_gd", password="x", name="홍", contact="010-1111-2222")
        with self.assertRaises(IntegrityError), transaction.atomic():
            Member.objects.create_user(username="hong_GD", password="x", name="홍2", contact="010-1111-3333")
        with self.assertRaises(services.DuplicateAccountError):
            services.sign_up(member_type="CUSTOMER", username="HONG_GD", password="x", name="홍3", address="",
                             contact="010-1111-4444")


class SignUpDBErrorTests(TestCase):
    """UC1 5a: 가입 저장 중 DB 오류는 가입 여부를 확인해 안내한다."""

    url = reverse("members:signup")

    def test_not_saved(self):
        from unittest import mock

        from django.db import DatabaseError

        from . import services
        with mock.patch.object(services, "sign_up", side_effect=DatabaseError("끊김")):
            with self.assertLogs("apps.members.views", "ERROR"):
                res = self.client.post(self.url, signup_data())
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, "가입되지 않았습니다")
        self.assertFalse(Member.objects.exists())

    def test_saved_then_error(self):
        from unittest import mock

        from django.db import DatabaseError

        from . import services
        original = services.sign_up

        def saved_then_error(**kw):
            original(**kw)
            raise DatabaseError("응답 전 끊김")

        with mock.patch.object(services, "sign_up", side_effect=saved_then_error):
            with self.assertLogs("apps.members.views", "ERROR"):
                res = self.client.post(self.url, signup_data(), follow=True)
        self.assertRedirects(res, reverse("members:login"))
        self.assertContains(res, "가입은 완료되었습니다")
