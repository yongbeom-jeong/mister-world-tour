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
