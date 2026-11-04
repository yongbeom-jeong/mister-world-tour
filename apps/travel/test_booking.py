"""UC4 상품 선택·구성, UC5 여행 신청 (검토·등록·출발 확정·문자 대상)."""

import json
from datetime import timedelta
from unittest import mock

from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from apps.members.models import Member
from apps.sms.services import ConsoleSMSClient
from apps.souvenir.models import Souvenir

from . import booking
from . import configuration as cfg
from .choices import DepartureStatus, Theme, TourLevel
from .models import TravelApplication, TravelHistory, TravelProduct


def make_product(theme=Theme.GOLF, level=TourLevel.GRAND, price=1_000_000, days_ahead=30, name=None):
    p = TravelProduct(name=name or f"{theme} 상품", destination="어딘가", theme=theme, tour_level=level,
                      base_price=price, travel_days=4,
                      departure_date=timezone.localdate() + timedelta(days=days_ahead),
                      souvenir=Souvenir.objects.filter(theme=theme).first())
    p.apply_default_configuration()
    p.save()
    return p


def customer(username, name=None, contact="010-1111-2222"):
    return Member.objects.create_user(username=username, password="x", name=name or username, contact=contact)


class BookingTestBase(TestCase):
    def setUp(self):
        Souvenir.objects.update(stock_quantity=100)
        ConsoleSMSClient.sent = []
        self.alice = customer("alice", "앨리스", "010-1000-0001")

    def rows(self, member, *companions):
        return [{"name": member.name, "contact": member.contact}] + [{"name": n, "contact": ""} for n in companions]

    def book(self, member, product, *companions, config=None):
        review = booking.review_application(member, product.pk, config or cfg.default_configuration(product),
                                            self.rows(member, *companions))
        return booking.submit_travel_application(member, review)


class ConfigurationTests(BookingTestBase):
    def test_change_one_option_updates_price(self):
        p = make_product(level=TourLevel.CLASSIC)  # 3등급 호텔·도시락
        new, price = booking.configure_travel_product(p, cfg.default_configuration(p), {"hotel_level": "STAR5"})
        self.assertEqual(new["hotel_level"], "STAR5")
        self.assertEqual(new["meal"], "LUNCHBOX")  # 바꾸지 않은 항목은 유지
        self.assertEqual(price, 1_350_000)

    def test_invalid_change_keeps_previous(self):
        p = make_product(level=TourLevel.CLASSIC)
        current = cfg.default_configuration(p)
        with self.assertRaises(cfg.ConfigurationError) as cm:
            booking.configure_travel_product(p, current, {"drinks": ["WINE"]})  # 도시락 + 음료
        self.assertIn("drinks", cm.exception.errors)
        self.assertEqual(current, cfg.default_configuration(p))  # 직전 구성은 그대로

    def test_transport_rules_by_theme(self):
        honeymoon = make_product(Theme.HONEYMOON)
        with self.assertRaises(cfg.ConfigurationError):
            booking.configure_travel_product(honeymoon, cfg.default_configuration(honeymoon),
                                             {"transport": "LUXURY_VAN_10"})
        new, _ = booking.configure_travel_product(honeymoon, cfg.default_configuration(honeymoon),
                                                  {"transport": "LIMOUSINE"})
        self.assertEqual(new["transport"], "LIMOUSINE")

    def test_included_champagne_not_charged(self):
        p = make_product(level=TourLevel.PREMIUM)  # 고급 식사(샴페인 포함)
        _, price = booking.configure_travel_product(p, cfg.default_configuration(p), {"drinks": ["CHAMPAGNE"]})
        self.assertEqual(price, 1_000_000)

    def test_configure_endpoint(self):
        p = make_product(level=TourLevel.CLASSIC)
        self.client.force_login(self.alice)
        url = reverse("travel:configure", args=[p.pk])
        ok = self.client.post(url, json.dumps({"current": cfg.default_configuration(p), "changes": {"meal": "LOCAL"}}),
                              content_type="application/json").json()
        self.assertEqual(ok["unit_price"], 1_080_000)
        bad = self.client.post(url, json.dumps({"current": ok["config"], "changes": {"hotel_level": "STAR9"}}),
                               content_type="application/json")
        self.assertEqual(bad.status_code, 400)


class BrowseTests(BookingTestBase):
    def test_list_hides_past_products_and_filters(self):
        make_product(Theme.GOLF, name="미래 골프")
        make_product(Theme.GOLF, name="지난 골프", days_ahead=0)
        make_product(Theme.TREKKING, name="트레킹")
        self.client.force_login(self.alice)
        res = self.client.get(reverse("travel:products"), {"theme": "GOLF"})
        self.assertEqual([p.name for p in res.context["products"]], ["미래 골프"])

    def test_honeymoon_classic_combination_explained(self):
        self.client.force_login(self.alice)
        res = self.client.get(reverse("travel:products"), {"theme": "HONEYMOON", "tour_level": "CLASSIC"})
        self.assertEqual(res.context["products"], [])
        self.assertContains(res, "그랜드 또는 프리미엄")

    def test_staff_cannot_browse_as_customer(self):
        staff = Member.objects.create_user(username="s", password="x", name="직원", contact="010",
                                           role=Member.Role.STAFF)
        self.client.force_login(staff)
        self.assertEqual(self.client.get(reverse("travel:products")).status_code, 403)


class ReviewTests(BookingTestBase):
    def test_review_saves_nothing(self):
        p = make_product()
        booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice, "밥"))
        self.assertFalse(TravelApplication.objects.exists())

    def test_honeymoon_requires_exactly_two(self):
        p = make_product(Theme.HONEYMOON)
        for companions in ((), ("a", "b")):
            with self.subTest(n=len(companions) + 1), self.assertRaises(booking.BookingError):
                booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                           self.rows(self.alice, *companions))

    def test_capacity_counts_existing_travelers(self):
        p = make_product()
        bob = customer("bob")
        self.book(bob, p, *[f"동반{i}" for i in range(7)])  # 8명
        with self.assertRaises(booking.BookingError) as cm:
            booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                       self.rows(self.alice, "x", "y"))  # 8 + 3 > 10
        self.assertIn("정원", cm.exception.message)

    def test_souvenir_stock_shortage(self):
        p = make_product()
        Souvenir.objects.filter(pk=p.souvenir_id).update(stock_quantity=1)
        with self.assertRaises(booking.BookingError) as cm:
            booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice, "밥"))
        self.assertIn("재고", cm.exception.message)

    def test_regular_customer_discount(self):
        today = timezone.localdate()
        for i in range(3):
            TravelHistory.objects.create(member=self.alice, completed_travel_id=f"t{i}", product_name="p",
                                         tour_level=TourLevel.GRAND, travel_days=3, price=1, completed=True,
                                         completed_at=today - timedelta(days=100 * (i + 1)))
        p = make_product()
        r = booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice, "밥"))
        self.assertEqual((r.breakdown.subtotal, r.breakdown.discount_amount, r.breakdown.final_amount),
                         (2_000_000, 200_000, 1_800_000))

    def test_invalid_companion_contact(self):
        p = make_product()
        with self.assertRaises(booking.BookingError) as cm:
            booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                       [{"name": "x", "contact": ""}, {"name": "밥", "contact": "123"}])
        self.assertIn("traveler_1", cm.exception.errors)


class SubmitTests(BookingTestBase):
    def test_group_confirms_at_three_and_notifies_all_applicants(self):
        p = make_product()
        bob = customer("bob", "밥", "010-2000-0002")
        first = self.book(bob, p, "밥동반")  # 2명
        self.assertEqual(first.departure_status, DepartureStatus.PENDING)
        self.assertIsNone(first.sms)

        second = self.book(self.alice, p)  # +1 → 3명
        self.assertTrue(second.newly_confirmed)
        self.assertEqual(second.departure_status, DepartureStatus.CONFIRMED)
        self.assertEqual({r.member_id for r in second.recipients}, {bob.pk, self.alice.pk})
        self.assertEqual(second.sms.success, 2)

        carol = customer("carol", "캐럴", "010-3000-0003")
        third = self.book(carol, p)  # 이미 확정된 상품의 추가 신청
        self.assertFalse(third.newly_confirmed)
        self.assertEqual([r.member_id for r in third.recipients], [carol.pk])

    def test_same_customer_twice_gets_one_message(self):
        p = make_product()
        self.book(self.alice, p)
        result = self.book(self.alice, p, "동반")  # 3명 → 확정, 신청 고객은 앨리스 한 명
        self.assertEqual([r.member_id for r in result.recipients], [self.alice.pk])
        self.assertEqual(len(ConsoleSMSClient.sent), 1)

    def test_honeymoon_each_application_confirmed_independently(self):
        p = make_product(Theme.HONEYMOON)
        r1 = self.book(self.alice, p, "배우자")
        bob = customer("bob")
        r2 = self.book(bob, p, "배우자2")
        for r, m in ((r1, self.alice), (r2, bob)):
            self.assertEqual(r.departure_status, DepartureStatus.CONFIRMED)
            self.assertEqual([x.member_id for x in r.recipients], [m.pk])
        p.refresh_from_db()
        self.assertIsNone(p.departure_status)  # 상품 공동 상태 없음

    def test_resubmit_same_key_does_not_duplicate(self):
        p = make_product()
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice))
        booking.submit_travel_application(self.alice, review)
        again = booking.submit_travel_application(self.alice, review)
        self.assertTrue(again.duplicate)
        self.assertEqual(TravelApplication.objects.count(), 1)
        self.assertEqual(booking.current_traveler_count(p.pk), 1)

    def test_price_change_after_review_is_not_saved(self):
        p = make_product()
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice))
        TravelProduct.objects.filter(pk=p.pk).update(base_price=1_200_000)
        with self.assertRaises(booking.PriceChanged):
            booking.submit_travel_application(self.alice, review)
        self.assertFalse(TravelApplication.objects.exists())

    def test_capacity_rechecked_at_submit(self):
        p = make_product()
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                            self.rows(self.alice, "a", "b"))  # 3명, 검토 시점엔 가능
        bob = customer("bob")
        self.book(bob, p, *[f"x{i}" for i in range(7)])  # 그 사이 8명 신청
        with self.assertRaises(booking.BookingError):
            booking.submit_travel_application(self.alice, review)
        self.assertEqual(booking.current_traveler_count(p.pk), 8)

    def test_sms_failure_keeps_application(self):
        p = make_product(Theme.HONEYMOON)
        with mock.patch("apps.sms.services.send_departure_confirmation", side_effect=RuntimeError("boom")):
            with self.assertLogs("apps.travel.booking", "ERROR"):
                result = self.book(self.alice, p, "배우자")
        self.assertEqual(result.departure_status, DepartureStatus.CONFIRMED)
        self.assertTrue(TravelApplication.objects.filter(pk=result.application.pk).exists())
        self.assertEqual(result.sms.failed, 1)

    def test_other_customer_cannot_submit_review(self):
        p = make_product()
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice))
        with self.assertRaises(booking.BookingError):
            booking.submit_travel_application(customer("mallory"), review)


class ApplyFlowHttpTests(BookingTestBase):
    def test_full_flow(self):
        p = make_product(Theme.HONEYMOON, TourLevel.PREMIUM)
        self.client.force_login(self.alice)
        config = cfg.default_configuration(p)
        res = self.client.post(reverse("travel:start_application", args=[p.pk]), {"config": json.dumps(config)})
        self.assertRedirects(res, reverse("travel:apply"))

        res = self.client.post(reverse("travel:apply"), {
            "action": "review", "traveler_name": ["무시됨", "배우자"], "traveler_contact": ["", "01099990000"],
        })
        review = res.context["review"]
        self.assertEqual(review.breakdown.traveler_count, 2)
        self.assertEqual(review.travelers[0]["name"], "앨리스")  # 첫 행은 계정 정보
        self.assertEqual(review.travelers[1]["contact"], "010-9999-0000")

        res = self.client.post(reverse("travel:apply"), {"action": "submit", "request_key": review.request_key},
                               follow=True)
        app = TravelApplication.objects.get()
        self.assertRedirects(res, reverse("travel:application_detail", args=[app.pk]))
        self.assertContains(res, "출발 확정")
        self.assertContains(res, "출발 확정 문자: 성공 1건")

        # 뒤로 가기 후 같은 키로 다시 제출해도 중복 저장되지 않음 (검토 내용은 이미 소진)
        self.client.post(reverse("travel:apply"), {"action": "submit", "request_key": review.request_key})
        self.assertEqual(TravelApplication.objects.count(), 1)

    def test_apply_without_selection_redirects(self):
        self.client.force_login(self.alice)
        self.assertRedirects(self.client.get(reverse("travel:apply")), reverse("travel:products"))

    def test_cannot_view_others_application(self):
        p = make_product()
        result = self.book(customer("bob"), p)
        self.client.force_login(self.alice)
        res = self.client.get(reverse("travel:application_detail", args=[result.application.pk]))
        self.assertEqual(res.status_code, 404)


@override_settings(SMS_CLIENT="apps.sms.services.ConsoleSMSClient")
class SeedWithApplicationsTests(TestCase):
    def test_seed_creates_demo_applications_once(self):
        from io import StringIO

        from django.core.management import call_command
        call_command("seed_demo", stdout=StringIO())
        call_command("seed_demo", stdout=StringIO())
        golf = TravelProduct.objects.get(name="다낭 골프 챌린지")
        filial = TravelProduct.objects.get(name="제주 힐링 효도 여행")
        self.assertEqual(booking.current_traveler_count(golf.pk), 2)
        self.assertEqual(golf.departure_status, DepartureStatus.PENDING)
        self.assertEqual(booking.remaining_seats(filial), 2)
        self.assertEqual(filial.departure_status, DepartureStatus.CONFIRMED)
