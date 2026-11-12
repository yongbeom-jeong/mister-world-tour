"""UC4 상품 선택·구성, UC5 여행 신청 (검토·등록·출발 확정·문자 대상)."""

import json
from datetime import timedelta
from unittest import mock

from django.db import DatabaseError
from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from apps.members.models import Member
from apps.sms.services import ConsoleSMSClient
from apps.souvenir import services as souvenir_services
from apps.souvenir.models import Souvenir, StockIssue

from . import booking
from . import configuration as cfg
from .choices import DepartureStatus, Theme, TourLevel
from .models import TravelApplication, TravelHistory, TravelProduct


def make_product(theme=Theme.GOLF, level=TourLevel.GRAND, price=1_000_000, days_ahead=30, name=None):
    p = TravelProduct(name=name or f"{theme} 상품", destination="어딘가", theme=theme, tour_level=level,
                      base_price=price, travel_days=4,
                      departure_date=timezone.localdate() + timedelta(days=days_ahead))
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
        Souvenir.objects.filter(theme=p.theme).update(stock_quantity=1)
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

    def test_resubmit_same_key_with_different_content_is_rejected(self):
        p = make_product()
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                            self.rows(self.alice, "동반"))
        booking.submit_travel_application(self.alice, review)
        changed_travelers = booking.Review(review.request_key, review.product_id, review.member_id, review.config,
                                           review.travelers[:1] + [{"name": "다른 사람", "contact": "",
                                                                    "is_applicant": False}], review.breakdown)
        other_hotel = "STAR3" if review.config["hotel_level"] != "STAR3" else "STAR4"
        changed_config = booking.Review(review.request_key, review.product_id, review.member_id,
                                        {**review.config, "hotel_level": other_hotel}, review.travelers,
                                        review.breakdown)
        for changed in (changed_travelers, changed_config):
            with self.assertRaises(booking.BookingError):
                booking.submit_travel_application(self.alice, changed)
        self.assertEqual(TravelApplication.objects.count(), 1)

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
        with mock.patch("apps.sms.services.deliver_departure_confirmation", side_effect=RuntimeError("boom")):
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


class SouvenirDeductionTests(BookingTestBase):
    """출발 확정 시 테마 기념품 차감 (테마 품목마다 여행자 1인당 1개)."""

    def stock(self, name):
        return Souvenir.objects.get(name=name).stock_quantity

    def test_group_deducts_all_travelers_when_confirmed(self):
        p = make_product(Theme.GOLF)
        bob = customer("bob")
        self.book(bob, p, "동반")  # 2명, 확정 전 → 차감 없음
        self.assertEqual(self.stock("골프공"), 100)
        result = self.book(self.alice, p)  # 3명 → 확정, 3개 차감
        self.assertEqual(self.stock("골프공"), 97)
        self.assertEqual(result.souvenirs_issued, 3)
        self.book(customer("carol"), p, "x")  # 확정 뒤 추가 2명 → 그 신청분만
        self.assertEqual(self.stock("골프공"), 95)
        self.assertEqual(StockIssue.objects.count(), 3)

    def test_honeymoon_deducts_per_application(self):
        p = make_product(Theme.HONEYMOON)
        self.book(self.alice, p, "배우자")
        self.assertEqual(self.stock("커플 기념티"), 98)

    def test_every_souvenir_of_theme_is_deducted(self):
        extra = souvenir_services.register_souvenir("골프 장갑", Theme.GOLF)
        Souvenir.objects.filter(pk=extra.pk).update(stock_quantity=10)
        p = make_product(Theme.GOLF)
        self.book(self.alice, p, "a", "b")  # 3명 → 확정
        self.assertEqual((self.stock("골프공"), self.stock("골프 장갑")), (97, 7))
        self.assertEqual(booking.theme_souvenir_names(p), ["골프공", "골프 장갑"])

    def test_other_theme_not_deducted(self):
        self.book(self.alice, make_product(Theme.GOLF), "a", "b")
        self.assertEqual(self.stock("기념품 스카프"), 100)

    def test_confirmation_needs_stock_for_all_pending_travelers(self):
        p = make_product(Theme.GOLF)
        self.book(customer("bob"), p, "동반")  # 2명 대기
        Souvenir.objects.filter(name="골프공").update(stock_quantity=2)
        with self.assertRaises(booking.BookingError) as cm:  # 확정되면 3개 필요
            self.book(self.alice, p)
        self.assertIn("3개가 필요", cm.exception.message)
        self.assertEqual(TravelApplication.objects.count(), 1)  # 신청도 저장되지 않음
        self.assertEqual(self.stock("골프공"), 2)

    def test_shortage_at_submit_rolls_back_everything(self):
        extra = souvenir_services.register_souvenir("골프 장갑", Theme.GOLF)
        Souvenir.objects.filter(pk=extra.pk).update(stock_quantity=10)
        p = make_product(Theme.GOLF)
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                            self.rows(self.alice, "a", "b"))
        Souvenir.objects.filter(pk=extra.pk).update(stock_quantity=1)  # 검토 뒤 재고가 줄어듦
        with self.assertRaises(booking.BookingError):
            booking.submit_travel_application(self.alice, review)
        self.assertFalse(TravelApplication.objects.exists())
        self.assertEqual(self.stock("골프공"), 100)  # 앞 품목 차감도 되돌아감
        self.assertFalse(StockIssue.objects.exists())

    def test_theme_without_souvenir_cannot_apply(self):
        p = make_product(Theme.TREKKING)
        StockIssue.objects.all().delete()
        Souvenir.objects.filter(theme=Theme.TREKKING).delete()
        with self.assertRaises(booking.BookingError) as cm:
            booking.review_application(self.alice, p.pk, cfg.default_configuration(p), self.rows(self.alice))
        self.assertIn("기념품 정보가 없어", cm.exception.message)


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

        # 뒤로 가기 후 같은 키로 다시 제출: 중복 저장 없이 기존 신청으로 안내
        res = self.client.post(reverse("travel:apply"), {"action": "submit", "request_key": review.request_key},
                               follow=True)
        self.assertRedirects(res, reverse("travel:application_detail", args=[app.pk]))
        self.assertContains(res, "이미 처리된 신청입니다")
        self.assertEqual(TravelApplication.objects.count(), 1)

    def reviewed(self):
        """그룹 상품 1명 신청을 검토까지 진행하고 요청 키를 돌려준다."""
        p = make_product()
        self.client.force_login(self.alice)
        self.client.post(reverse("travel:start_application", args=[p.pk]),
                         {"config": json.dumps(cfg.default_configuration(p))})
        res = self.client.post(reverse("travel:apply"), {"action": "review", "traveler_name": ["무시됨"],
                                                         "traveler_contact": [""]})
        return res.context["review"].request_key

    def submit(self, key):
        return self.client.post(reverse("travel:apply"), {"action": "submit", "request_key": key})

    def test_db_error_before_save_keeps_review_for_retry(self):
        key = self.reviewed()
        with mock.patch.object(booking, "submit_travel_application", side_effect=DatabaseError("끊김")):
            with self.assertLogs("apps.travel.customer_views", "ERROR"):
                res = self.submit(key)
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, "신청이 저장되지 않았습니다")
        self.assertEqual(res.context["review"].request_key, key)  # 같은 키로 다시 제출 가능
        self.assertFalse(TravelApplication.objects.exists())
        res = self.submit(key)  # 다시 누르면 정상 저장
        self.assertEqual(TravelApplication.objects.count(), 1)

    def test_db_error_after_save_shows_saved_application(self):
        key = self.reviewed()
        original = booking.submit_travel_application

        def saved_then_error(*args, **kwargs):
            original(*args, **kwargs)
            raise DatabaseError("응답 전 끊김")

        with mock.patch.object(booking, "submit_travel_application", side_effect=saved_then_error):
            with self.assertLogs("apps.travel.customer_views", "ERROR"):
                res = self.submit(key)
        app = TravelApplication.objects.get()
        self.assertRedirects(res, reverse("travel:application_detail", args=[app.pk]))
        self.assertNotIn(key, self.client.session.get("application_reviews", {}))

    def test_db_error_and_lookup_failure_says_unknown(self):
        key = self.reviewed()
        with mock.patch.object(booking, "submit_travel_application", side_effect=DatabaseError("끊김")), \
                mock.patch.object(booking, "find_submitted_application", side_effect=DatabaseError("끊김")):
            with self.assertLogs("apps.travel.customer_views", "ERROR"):
                res = self.submit(key)
        self.assertContains(res, "반영 여부를 확인하지 못했습니다")

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


class CancelTests(BookingTestBase):
    def test_cancel_before_confirmation_frees_seats(self):
        p = make_product()
        r = self.book(self.alice, p, "동반")
        booking.cancel_application(self.alice, r.application.pk)
        r.application.refresh_from_db()
        self.assertTrue(r.application.is_cancelled)
        self.assertEqual(booking.current_traveler_count(p.pk), 0)
        self.assertEqual(booking.remaining_seats(p), 10)

    def test_cancelled_applicant_not_counted_or_notified_at_confirmation(self):
        p = make_product()
        first = self.book(self.alice, p, "동반")  # 2명
        booking.cancel_application(self.alice, first.application.pk)
        bob = customer("bob", contact="010-2000-0002")
        r = self.book(bob, p, "a")  # 2명: 취소분을 빼면 확정 안 됨
        self.assertFalse(r.newly_confirmed)
        r = self.book(customer("carol", contact="010-3000-0003"), p)  # 3명째 → 확정
        self.assertTrue(r.newly_confirmed)
        self.assertNotIn(self.alice.pk, [x.member_id for x in r.recipients])
        self.assertFalse(StockIssue.objects.filter(application=first.application).exists())

    def test_confirmed_cannot_be_cancelled(self):
        p = make_product()
        r = self.book(self.alice, p, "a", "b")  # 3명 → 확정
        with self.assertRaises(booking.BookingError) as cm:
            booking.cancel_application(self.alice, r.application.pk)
        self.assertIn("확정", cm.exception.message)
        honeymoon = self.book(customer("bob"), make_product(Theme.HONEYMOON), "배우자")
        self.assertIsNotNone(booking.cancel_block_reason(honeymoon.application))

    def test_cannot_cancel_twice_or_others(self):
        p = make_product()
        r = self.book(self.alice, p)
        with self.assertRaises(booking.BookingError):
            booking.cancel_application(customer("mallory"), r.application.pk)
        booking.cancel_application(self.alice, r.application.pk)
        with self.assertRaises(booking.BookingError):
            booking.cancel_application(self.alice, r.application.pk)

    def test_cancel_http_flow(self):
        p = make_product()
        r = self.book(self.alice, p)
        self.client.force_login(self.alice)
        detail = reverse("travel:application_detail", args=[r.application.pk])
        self.assertContains(self.client.get(detail), "출발이 확정되면 신청을 취소할 수 없어요")
        res = self.client.post(reverse("travel:cancel_application", args=[r.application.pk]), follow=True)
        self.assertRedirects(res, detail)
        self.assertContains(res, "신청을 취소했습니다")
        self.assertContains(res, "취소한 신청")
        self.assertContains(self.client.get(reverse("travel:my_applications")), "취소함")

    def test_cancel_others_application_404(self):
        r = self.book(customer("bob"), make_product())
        self.client.force_login(self.alice)
        res = self.client.post(reverse("travel:cancel_application", args=[r.application.pk]))
        self.assertEqual(res.status_code, 404)

    def test_apply_review_shows_cancel_rule(self):
        p = make_product()
        self.client.force_login(self.alice)
        self.client.post(reverse("travel:start_application", args=[p.pk]),
                         {"config": json.dumps(cfg.default_configuration(p))})
        res = self.client.post(reverse("travel:apply"), {"action": "review", "traveler_name": ["x"],
                                                         "traveler_contact": [""]})
        self.assertContains(res, "출발이 확정되면 신청을 취소할 수 없어요")


class TripCompletionTests(BookingTestBase):
    """다녀온 여행(출발 확정 + 마지막 날 지남)만 완료 이력이 된다."""

    def travel(self, product, days_ago):
        """신청 뒤 출발일을 과거로 옮긴다 (여행 기간 4일)."""
        TravelProduct.objects.filter(pk=product.pk).update(
            departure_date=timezone.localdate() - timedelta(days=days_ago))

    def histories(self, member):
        return list(TravelHistory.objects.filter(member=member))

    def test_confirmed_trip_becomes_history_after_last_day(self):
        p = make_product(Theme.HONEYMOON, TourLevel.PREMIUM)
        r = self.book(self.alice, p, "배우자")
        self.travel(p, 3)  # 4일 여행의 마지막 날이 오늘 → 아직 여행 중
        from . import services
        self.assertEqual(services.complete_finished_trips(self.alice.pk), 0)
        self.travel(p, 4)  # 어제 끝남
        self.assertEqual(services.complete_finished_trips(self.alice.pk), 1)
        h = self.histories(self.alice)[0]
        self.assertTrue(h.completed)
        self.assertEqual(h.completed_at, timezone.localdate() - timedelta(days=1))
        self.assertEqual((h.product_name, h.price), (p.name, r.application.final_amount))
        self.assertEqual(services.complete_finished_trips(self.alice.pk), 0)  # 중복 없음

    def test_unconfirmed_or_cancelled_trip_is_not_history(self):
        from . import services
        pending = make_product(name="미확정")
        self.book(self.alice, pending)  # 1명 → 미확정
        cancelled = make_product(name="취소")
        r = self.book(self.alice, cancelled)
        booking.cancel_application(self.alice, r.application.pk)
        for p in (pending, cancelled):
            self.travel(p, 30)
        self.assertEqual(services.complete_finished_trips(), 0)

    def test_every_applicant_of_group_gets_history(self):
        from . import services
        p = make_product()
        bob = customer("bob", contact="010-2000-0002")
        self.book(self.alice, p, "a")
        self.book(bob, p)  # 3명 → 확정
        self.travel(p, 10)
        self.assertEqual(services.complete_finished_trips(), 2)
        self.assertEqual(len(self.histories(self.alice)), 1)
        self.assertEqual(len(self.histories(bob)), 1)

    def test_history_popup_and_discount_reflect_finished_trips(self):
        trips = []
        for i in range(3):  # 다녀온 허니문 3번 → 단골
            p = make_product(Theme.HONEYMOON, TourLevel.PREMIUM, name=f"다녀온 여행 {i}")
            self.book(self.alice, p, "배우자")
            trips.append(p)
        for p in trips:
            self.travel(p, 20)
        self.client.force_login(self.alice)
        res = self.client.get(reverse("travel:history"))
        self.assertContains(res, "다녀온 여행 0")
        review = booking.review_application(self.alice, make_product().pk, cfg.default_configuration(make_product()),
                                            self.rows(self.alice))
        self.assertTrue(review.breakdown.is_regular)

    def test_complete_trips_command(self):
        from io import StringIO

        from django.core.management import call_command
        p = make_product(Theme.HONEYMOON, TourLevel.PREMIUM)
        self.book(self.alice, p, "배우자")
        self.travel(p, 10)
        out = StringIO()
        call_command("complete_trips", stdout=out)
        self.assertIn("1건", out.getvalue())


class DepartureSMSLogTests(BookingTestBase):
    def test_confirmation_sms_is_logged_per_recipient(self):
        from apps.sms.models import SMSLog
        p = make_product()
        self.book(self.alice, p, "a")
        self.book(customer("bob", contact="010-2000-0002"), p)  # 3명 → 확정, 2명에게 문자
        logs = SMSLog.objects.filter(product_id=p.pk)
        self.assertEqual(sorted(logs.values_list("recipient_name", flat=True)), ["bob", "앨리스"])
        self.assertTrue(all(s == "SUCCESS" for s in logs.values_list("status", flat=True)))


class TripCountTests(BookingTestBase):
    def test_several_applications_to_same_trip_count_once(self):
        from . import services
        p = make_product()
        first = self.book(self.alice, p, "a", "b")  # 3명 → 확정
        second = self.book(self.alice, p, "c")      # 같은 여행에 1명 추가 신청
        TravelProduct.objects.filter(pk=p.pk).update(departure_date=timezone.localdate() - timedelta(days=10))
        self.assertEqual(services.complete_finished_trips(self.alice.pk), 1)
        h = TravelHistory.objects.get(member=self.alice)
        self.assertEqual(h.price, first.application.final_amount + second.application.final_amount)
        self.assertEqual(services.complete_finished_trips(self.alice.pk), 0)

    def test_three_applications_to_one_trip_do_not_make_regular(self):
        p = make_product()
        for i in range(3):
            self.book(self.alice, p, *([f"x{i}a", f"x{i}b"] if i == 0 else []))
        TravelProduct.objects.filter(pk=p.pk).update(departure_date=timezone.localdate() - timedelta(days=10))
        q = make_product(name="다음 여행")
        review = booking.review_application(self.alice, q.pk, cfg.default_configuration(q), self.rows(self.alice))
        self.assertFalse(review.breakdown.is_regular)


class ProductListLoadTests(BookingTestBase):
    def test_load_failure_is_not_shown_as_empty(self):
        from django.db import DatabaseError
        self.client.force_login(self.alice)
        with mock.patch.object(booking, "bookable_products", side_effect=DatabaseError("끊김")):
            with self.assertLogs("apps.travel.customer_views", "ERROR"):
                res = self.client.get(reverse("travel:products"))
        self.assertEqual(res.status_code, 503)
        self.assertContains(res, "상품 목록을 불러오지 못했습니다", status_code=503)
        self.assertContains(res, "다시 조회", status_code=503)
        self.assertNotContains(res, "상품이 없습니다", status_code=503)

    def test_no_products_vs_no_match(self):
        self.client.force_login(self.alice)
        self.assertContains(self.client.get(reverse("travel:products")), "지금 신청할 수 있는 여행 상품이 없습니다")
        make_product(Theme.GOLF)
        res = self.client.get(reverse("travel:products") + "?theme=TREKKING")
        self.assertContains(res, "조건에 맞는 상품이 없습니다")


class ReviewDBErrorTests(BookingTestBase):
    def test_price_calculation_failure_is_reported(self):
        from django.db import DatabaseError
        p = make_product()
        self.client.force_login(self.alice)
        self.client.post(reverse("travel:start_application", args=[p.pk]),
                         {"config": json.dumps(cfg.default_configuration(p))})
        with mock.patch.object(booking, "review_application", side_effect=DatabaseError("끊김")):
            with self.assertLogs("apps.travel.customer_views", "ERROR"):
                res = self.client.post(reverse("travel:apply"), {"action": "review", "traveler_name": ["x", "동반"],
                                                                 "traveler_contact": ["", ""]})
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, "신청 금액을 계산하지 못했습니다")
        self.assertContains(res, "동반")  # 입력 유지
        self.assertIsNone(res.context["review"])


class SMSLogBeforeSendTests(BookingTestBase):
    """설계 05장 §2.2: 신청 저장과 함께 문자 기록을 먼저 만들어, 보내기 전에 멈춰도 기록이 남는다."""

    def test_log_exists_even_if_sending_never_happens(self):
        from apps.sms.models import SMSLog
        p = make_product(Theme.HONEYMOON)
        with mock.patch("apps.sms.services.deliver_departure_confirmation", side_effect=RuntimeError("서버 중단")):
            with self.assertLogs("apps.travel.booking", "ERROR"):
                self.book(self.alice, p, "배우자")
        log = SMSLog.objects.get(product_id=p.pk)
        self.assertEqual((log.status, log.attempt_count, log.recipient_name), ("PENDING", 0, "앨리스"))

    def test_rolled_back_application_leaves_no_log(self):
        from apps.sms.models import SMSLog
        p = make_product(Theme.GOLF)
        review = booking.review_application(self.alice, p.pk, cfg.default_configuration(p),
                                            self.rows(self.alice, "a", "b"))  # 3명 → 확정 예정
        Souvenir.objects.filter(theme=Theme.GOLF).update(stock_quantity=1)  # 검토 뒤 재고 부족
        with self.assertRaises(booking.BookingError):
            booking.submit_travel_application(self.alice, review)
        self.assertFalse(SMSLog.objects.exists())
