from django.test import TestCase
from django.urls import reverse

from apps.members.models import Member

from . import services
from .models import Souvenir, StockReceipt


class InitialSouvenirTests(TestCase):
    def test_four_theme_souvenirs_registered(self):
        self.assertEqual(Souvenir.objects.count(), 4)
        self.assertEqual(set(Souvenir.objects.values_list("theme", flat=True)),
                         {"HONEYMOON", "FILIAL", "GOLF", "TREKKING"})


class StockReceiptServiceTests(TestCase):
    """UC8 입고: 준비 → 등록, 같은 키 재시도는 재고를 다시 늘리지 않는다."""

    def setUp(self):
        self.staff = Member.objects.create_user(username="emp", password="x", name="직원", contact="010",
                                                role=Member.Role.STAFF)
        self.other = Member.objects.create_user(username="emp2", password="x", name="직원2", contact="010",
                                                role=Member.Role.STAFF)
        self.ball = Souvenir.objects.get(name="골프공")
        self.pending = {}

    def prepare(self, qty=5, sid=None, emp=None):
        return services.prepare_stock_receipt(self.pending, (emp or self.staff).pk, sid or self.ball.pk, qty)

    def record(self, key, qty=5, sid=None, emp=None):
        return services.record_souvenir_stock_receipt(self.pending, (emp or self.staff).pk, sid or self.ball.pk,
                                                      qty, key)

    def test_prepare_does_not_save(self):
        self.prepare()
        self.assertEqual(StockReceipt.objects.count(), 0)
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 0)

    def test_record_increases_stock_once(self):
        key = self.prepare(qty=5)["request_key"]
        res = self.record(key, qty=5)
        self.assertEqual(res.status, services.StockReceiptStatus.SUCCESS)
        self.assertEqual(res.current_souvenir.stock_quantity, 5)
        self.assertEqual(res.receipt.stock_after, 5)
        self.assertNotIn(key, self.pending)  # 확인된 입고는 문맥 종료

        again = self.record(key, qty=5)  # 같은 제출의 재시도
        self.assertEqual(again.status, services.StockReceiptStatus.SUCCESS)
        self.assertTrue(again.already_recorded)
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 5)
        self.assertEqual(StockReceipt.objects.count(), 1)

    def test_same_key_with_different_content_is_conflict(self):
        key = self.prepare(qty=5)["request_key"]
        self.assertEqual(self.record(key, qty=7).status, services.StockReceiptStatus.CONFLICT)
        self.record(key, qty=5)
        self.assertEqual(self.record(key, qty=5, emp=self.other).status, services.StockReceiptStatus.CONFLICT)
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 5)

    def test_unprepared_key_rejected(self):
        self.assertEqual(self.record("made-up-key").status, services.StockReceiptStatus.CONFLICT)
        self.assertEqual(StockReceipt.objects.count(), 0)

    def test_invalid_quantity_and_souvenir(self):
        for bad in ("0", "-3", "2.5", "abc", "", None, "10001", "²"):
            with self.subTest(bad):
                with self.assertRaises(services.StockReceiptError) as cm:
                    self.prepare(qty=bad)
                self.assertEqual(cm.exception.status, services.StockReceiptStatus.INVALID_INPUT)
        with self.assertRaises(services.StockReceiptError) as cm:
            self.prepare(sid=9999)
        self.assertEqual(cm.exception.status, services.StockReceiptStatus.NOT_FOUND)

    def test_separate_receipts_accumulate(self):
        for q in (3, 4):
            key = self.prepare(qty=q)["request_key"]
            self.record(key, qty=q)
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 7)


class StockReceiptViewTests(TestCase):
    def setUp(self):
        self.staff = Member.objects.create_user(username="emp", password="x", name="직원", contact="010",
                                                role=Member.Role.STAFF)
        self.ball = Souvenir.objects.get(name="골프공")

    def test_full_flow_through_http(self):
        self.client.force_login(self.staff)
        self.assertEqual(self.client.get(reverse("souvenir:stock")).status_code, 200)
        listing = self.client.get(reverse("souvenir:list")).json()
        self.assertEqual(len(listing["souvenirs"]), 4)

        prep = self.client.post(reverse("souvenir:prepare"), {"souvenir_id": self.ball.pk, "quantity": "12"}).json()
        body = {"souvenir_id": self.ball.pk, "quantity": "12", "request_key": prep["request_key"]}
        first = self.client.post(reverse("souvenir:record"), body)
        second = self.client.post(reverse("souvenir:record"), body)  # 더블 클릭·재전송
        self.assertEqual(first.json()["current_souvenir"]["stock_quantity"], 12)
        self.assertTrue(second.json()["already_recorded"])
        self.ball.refresh_from_db()
        self.assertEqual(self.ball.stock_quantity, 12)

        bad = self.client.post(reverse("souvenir:prepare"), {"souvenir_id": self.ball.pk, "quantity": "0"})
        self.assertEqual(bad.status_code, 400)

    def test_customer_forbidden(self):
        customer = Member.objects.create_user(username="c", password="x", name="고객", contact="010")
        self.client.force_login(customer)
        self.assertEqual(self.client.get(reverse("souvenir:stock")).status_code, 403)
        res = self.client.post(reverse("souvenir:prepare"), {"souvenir_id": 1, "quantity": 1})
        self.assertEqual(res.status_code, 403)


class SouvenirRegistrationTests(TestCase):
    def setUp(self):
        self.staff = Member.objects.create_user(username="staff9", password="x", name="직원", contact="010-1",
                                                role=Member.Role.STAFF)
        self.client.force_login(self.staff)
        self.url = reverse("souvenir:register")

    def test_register_links_theme_and_opens_stock_page(self):
        res = self.client.post(self.url, {"name": " 골프 장갑 ", "theme": "GOLF"})
        s = Souvenir.objects.get(name="골프 장갑")
        self.assertEqual((s.theme, s.stock_quantity), ("GOLF", 0))
        self.assertRedirects(res, reverse("souvenir:stock") + f"?selected={s.pk}")
        self.assertEqual(services.souvenir_names_for_theme("GOLF"), ["골프공", "골프 장갑"])

    def test_validation(self):
        cases = [({"name": "", "theme": "GOLF"}, "name"), ({"name": "골프공", "theme": "GOLF"}, "name"),
                 ({"name": "골프 장갑", "theme": ""}, "theme"), ({"name": "x" * 51, "theme": "GOLF"}, "name")]
        for data, field in cases:
            with self.subTest(data=data):
                res = self.client.post(self.url, data)
                self.assertEqual(res.status_code, 200)
                self.assertIn(field, res.context["errors"])
        self.assertEqual(Souvenir.objects.count(), 4)

    def test_page_lists_souvenirs_by_theme(self):
        res = self.client.get(self.url)
        self.assertContains(res, "커플 기념티")
        self.assertContains(res, "기념품 스카프")

    def test_customer_forbidden(self):
        customer = Member.objects.create_user(username="cust9", password="x", name="고객", contact="010-2")
        self.client.force_login(customer)
        self.assertEqual(self.client.get(self.url).status_code, 403)
        self.assertEqual(self.client.post(self.url, {"name": "a", "theme": "GOLF"}).status_code, 403)
        self.assertEqual(Souvenir.objects.count(), 4)


class SouvenirManageTests(TestCase):
    """직원: 기념품 수정·삭제·차감 내역."""

    def setUp(self):
        self.staff = Member.objects.create_user(username="staff8", password="x", name="직원", contact="010-1",
                                                role=Member.Role.STAFF)
        self.client.force_login(self.staff)
        self.ball = Souvenir.objects.get(name="골프공")

    def receive(self, souvenir, qty=5):
        pending = {}
        key = services.prepare_stock_receipt(pending, self.staff.pk, souvenir.pk, qty)["request_key"]
        services.record_souvenir_stock_receipt(pending, self.staff.pk, souvenir.pk, qty, key)

    def test_edit_name_and_theme(self):
        gloves = services.register_souvenir("골프 장갑", "GOLF")
        res = self.client.post(reverse("souvenir:edit", args=[gloves.pk]), {"name": "트레킹 장갑", "theme": "TREKKING"})
        self.assertRedirects(res, reverse("souvenir:manage"))
        gloves.refresh_from_db()
        self.assertEqual((gloves.name, gloves.theme), ("트레킹 장갑", "TREKKING"))
        self.assertIn("트레킹 장갑", services.souvenir_names_for_theme("TREKKING"))

    def test_edit_validation_and_duplicate_name(self):
        gloves = services.register_souvenir("골프 장갑", "GOLF")
        res = self.client.post(reverse("souvenir:edit", args=[gloves.pk]), {"name": "골프공", "theme": "GOLF"})
        self.assertIn("name", res.context["errors"])
        res = self.client.post(reverse("souvenir:edit", args=[gloves.pk]), {"name": "골프 장갑", "theme": "GOLF"})
        self.assertRedirects(res, reverse("souvenir:manage"))  # 자기 이름 그대로는 허용

    def test_moving_last_souvenir_warns_theme_unbookable(self):
        res = self.client.post(reverse("souvenir:edit", args=[self.ball.pk]), {"name": "골프공", "theme": "TREKKING"},
                               follow=True)
        self.assertContains(res, "이 테마 상품은 신청할 수 없습니다")

    def test_delete_without_history_removes_row(self):
        gloves = services.register_souvenir("골프 장갑", "GOLF")
        res = self.client.post(reverse("souvenir:delete", args=[gloves.pk]), follow=True)
        self.assertContains(res, "삭제했습니다")
        self.assertFalse(Souvenir.objects.filter(pk=gloves.pk).exists())

    def test_delete_with_history_hides_and_keeps_records(self):
        gloves = services.register_souvenir("골프 장갑", "GOLF")
        self.receive(gloves)
        res = self.client.post(reverse("souvenir:delete", args=[gloves.pk]), follow=True)
        self.assertContains(res, "내역은 기록으로 남겨 둡니다")
        gloves.refresh_from_db()
        self.assertFalse(gloves.is_active)
        self.assertEqual(StockReceipt.objects.filter(souvenir=gloves).count(), 1)
        self.assertNotIn("골프 장갑", services.souvenir_names_for_theme("GOLF"))  # 신청 검사·차감 제외
        self.assertNotIn(gloves, services.load_souvenirs())  # 입고 목록 제외
        with self.assertRaises(services.StockReceiptError):
            services.prepare_stock_receipt({}, self.staff.pk, gloves.pk, 1)
        self.assertEqual(self.client.get(reverse("souvenir:edit", args=[gloves.pk])).status_code, 302)
        with self.assertRaises(services.SouvenirRegistrationError):  # 숨긴 품목 이름은 다시 쓰지 않음
            services.register_souvenir("골프 장갑", "GOLF")

    def test_issue_list_shows_deductions(self):
        from datetime import timedelta

        from django.utils import timezone

        from apps.travel import booking
        from apps.travel import configuration as cfg
        from apps.travel.models import TravelProduct
        Souvenir.objects.update(stock_quantity=50)
        p = TravelProduct(name="다낭 골프", destination="다낭", theme="GOLF", tour_level="GRAND", base_price=1_000_000,
                          travel_days=4, departure_date=timezone.localdate() + timedelta(days=30))
        p.apply_default_configuration()
        p.save()
        customer = Member.objects.create_user(username="cust8", password="x", name="고객팔", contact="010-1234-5678")
        review = booking.review_application(customer, p.pk, cfg.default_configuration(p),
                                            [{"name": "", "contact": ""}, {"name": "a"}, {"name": "b"}])
        booking.submit_travel_application(customer, review)  # 3명 → 확정, 골프공 3개 차감
        res = self.client.get(reverse("souvenir:issues") + f"?souvenir={self.ball.pk}")
        self.assertContains(res, "다낭 골프")
        self.assertContains(res, "고객팔")
        self.assertContains(res, "−3개")
        self.assertContains(res, "47개")

    def test_manage_pages_staff_only(self):
        customer = Member.objects.create_user(username="cust7", password="x", name="고객", contact="010-2")
        self.assertContains(self.client.get(reverse("souvenir:manage")), "골프공")
        self.client.force_login(customer)
        for url in (reverse("souvenir:manage"), reverse("souvenir:issues"),
                    reverse("souvenir:edit", args=[self.ball.pk])):
            self.assertEqual(self.client.get(url).status_code, 403)
        self.assertEqual(self.client.post(reverse("souvenir:delete", args=[self.ball.pk])).status_code, 403)
        self.assertTrue(Souvenir.objects.filter(pk=self.ball.pk, is_active=True).exists())
