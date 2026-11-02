from datetime import date

from django.core.exceptions import ValidationError
from django.test import TestCase

from apps.members.models import Member

from .choices import DepartureStatus, HotelLevel, MealOption, Theme, TourLevel
from .models import TravelHistory, TravelProduct
from .pricing import calculate_application_price, calculate_unit_price


def make_product(theme=Theme.GOLF, level=TourLevel.GRAND, **kw):
    p = TravelProduct(name="테스트 상품", theme=theme, tour_level=level, base_price=1_000_000,
                      travel_days=5, departure_date=date(2026, 12, 1), **kw)
    p.apply_default_configuration()
    return p


class ProductRuleTests(TestCase):
    def test_default_configuration_from_theme_and_level(self):
        p = make_product(Theme.GOLF, TourLevel.PREMIUM)
        self.assertEqual(p.hotel_level, HotelLevel.STAR5)
        self.assertEqual(p.meal, MealOption.FINE)
        self.assertEqual(p.departure_status, DepartureStatus.PENDING)

    def test_honeymoon_has_no_product_departure_status(self):
        self.assertIsNone(make_product(Theme.HONEYMOON).departure_status)

    def test_honeymoon_and_filial_reject_classic(self):
        for theme in (Theme.HONEYMOON, Theme.FILIAL):
            with self.assertRaises(ValidationError):
                make_product(theme, TourLevel.CLASSIC).clean()
        make_product(Theme.TREKKING, TourLevel.CLASSIC).clean()  # 트레킹 클래식은 허용

    def test_honeymoon_needs_exactly_two(self):
        p = make_product(Theme.HONEYMOON)
        self.assertIsNotNone(p.validate_application_capacity(0, 1))
        self.assertIsNone(p.validate_application_capacity(0, 2))
        self.assertIsNone(p.validate_application_capacity(8, 2))  # 다른 신청과 합산하지 않음

    def test_group_capacity_ten(self):
        p = make_product()
        self.assertIsNone(p.validate_application_capacity(7, 3))
        self.assertIsNotNone(p.validate_application_capacity(8, 3))

    def test_departure_confirmed_at_three_and_stays(self):
        p = make_product()
        self.assertEqual(p.update_departure_status(2), DepartureStatus.PENDING)
        self.assertEqual(p.update_departure_status(3), DepartureStatus.CONFIRMED)
        self.assertEqual(p.update_departure_status(3), DepartureStatus.CONFIRMED)


class PricingTests(TestCase):
    def setUp(self):
        self.member = Member.objects.create_user(username="c1", password="x", name="고객", contact="010")

    def history(self, tid, completed_at, completed=True):
        return TravelHistory(member=self.member, completed_travel_id=tid, product_name="p",
                             tour_level=TourLevel.GRAND, travel_days=3, price=1, completed=completed,
                             completed_at=completed_at)

    def test_unit_price_with_options(self):
        p = make_product(Theme.GOLF, TourLevel.CLASSIC)
        default = {"hotel_level": p.hotel_level, "transport": p.transport, "meal": p.meal, "drinks": []}
        self.assertEqual(calculate_unit_price(p, default), 1_000_000)
        # 호텔 3→5등급(+35만), 식사 도시락→고급(+20만), 샴페인은 고급 식사에 포함(0), 와인(+5만)
        upgraded = {"hotel_level": "STAR5", "transport": p.transport, "meal": "FINE", "drinks": ["CHAMPAGNE", "WINE"]}
        self.assertEqual(calculate_unit_price(p, upgraded), 1_000_000 + 350_000 + 200_000 + 50_000)
        # 하향 변경은 차액만큼 차감: 프리미엄(5등급) → 4등급 호텔 −20만, 고급 → 현지식 −12만
        golf_premium = make_product(Theme.GOLF, TourLevel.PREMIUM)
        down = {"hotel_level": "STAR4", "transport": golf_premium.transport, "meal": "LOCAL", "drinks": []}
        self.assertEqual(calculate_unit_price(golf_premium, down), 1_000_000 - 200_000 - 120_000)
        # 올리고 내린 것이 섞이면 합산: 호텔 4→5등급 +20만, 식사 현지식→도시락 −8만
        grand = make_product(Theme.GOLF, TourLevel.GRAND)
        mixed = {"hotel_level": "STAR5", "transport": grand.transport, "meal": "LUNCHBOX", "drinks": []}
        self.assertEqual(calculate_unit_price(grand, mixed), 1_000_000 + 200_000 - 80_000)

    def test_unit_price_never_below_half_of_base(self):
        cheap = make_product(Theme.GOLF, TourLevel.PREMIUM)
        cheap.base_price = 300_000  # 호텔·식사를 모두 최저로 낮추면 −55만 → 하한 15만
        lowest = {"hotel_level": "STAR3", "transport": cheap.transport, "meal": "LUNCHBOX", "drinks": []}
        self.assertEqual(calculate_unit_price(cheap, lowest), 150_000)

    def test_regular_customer_gets_ten_percent(self):
        today = date(2026, 11, 2)
        hs = [self.history(f"t{i}", date(2025, 1, i + 1)) for i in range(3)]
        b = calculate_application_price(1_000_000, 2, hs, today)
        self.assertTrue(b.is_regular)
        self.assertEqual((b.subtotal, b.discount_amount, b.final_amount), (2_000_000, 200_000, 1_800_000))

    def test_not_regular_cases(self):
        today = date(2026, 11, 2)
        cases = {
            "2회뿐": [self.history(f"t{i}", date(2025, 1, 1)) for i in range(2)],
            "같은 여행 중복": [self.history("same", date(2025, 1, 1)) for _ in range(3)],
            "3년 지남": [self.history(f"t{i}", date(2023, 1, 1)) for i in range(3)],
            "미완료": [self.history(f"t{i}", date(2025, 1, 1), completed=False) for i in range(3)],
            "미래 완료일": [self.history(f"t{i}", date(2027, 1, 1)) for i in range(3)],
        }
        for label, hs in cases.items():
            with self.subTest(label):
                self.assertEqual(calculate_application_price(100, 1, hs, today).discount_amount, 0)


class HistoryViewTests(TestCase):
    url = "/travel/history/"

    def setUp(self):
        self.customer = Member.objects.create_user(username="c", password="x", name="고객", contact="010")
        self.other = Member.objects.create_user(username="o", password="x", name="남", contact="010")
        self.staff = Member.objects.create_user(username="s", password="x", name="직원", contact="010",
                                                role=Member.Role.STAFF)

    def add(self, member, name, completed_at):
        TravelHistory.objects.create(member=member, completed_travel_id=name, product_name=name,
                                     tour_level=TourLevel.GRAND, travel_days=3, price=1_234_000,
                                     completed=completed_at is not None, completed_at=completed_at)

    def test_recent_first_and_only_mine(self):
        self.add(self.customer, "옛날여행", date(2024, 1, 1))
        self.add(self.customer, "최근여행", date(2026, 5, 1))
        self.add(self.customer, "미완료여행", None)
        self.add(self.other, "남의여행", date(2026, 6, 1))
        self.client.force_login(self.customer)
        res = self.client.get(self.url)
        names = [h.product_name for h in res.context["histories"]]
        self.assertEqual(names, ["최근여행", "옛날여행", "미완료여행"])
        self.assertContains(res, "1,234,000원")

    def test_empty_history_message(self):
        self.client.force_login(self.customer)
        self.assertContains(self.client.get(self.url), "이전 여행 이력이 없습니다")

    def test_staff_and_anonymous_blocked(self):
        res = self.client.get(self.url)
        self.assertEqual(res.status_code, 302)  # 로그인 화면으로
        self.client.force_login(self.staff)
        self.assertEqual(self.client.get(self.url).status_code, 403)

    def test_lookup_failure_is_not_empty(self):
        from unittest import mock
        self.client.force_login(self.customer)
        with mock.patch("apps.travel.services.view_previous_travel_history", side_effect=RuntimeError):
            with self.assertLogs("apps.travel.views", "ERROR"):
                res = self.client.get(self.url)
        self.assertEqual(res.status_code, 500)
        self.assertContains(res, "불러오지 못했습니다", status_code=500)
