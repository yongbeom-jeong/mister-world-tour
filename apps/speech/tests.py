from datetime import timedelta

from django.test import SimpleTestCase, TestCase
from django.urls import reverse
from django.utils import timezone

from apps.members.models import Member
from apps.travel.models import TravelProduct

from . import services as sp

PRODUCTS = [
    sp.ProductCandidate(1, "몰디브 워터빌라 허니문", "몰디브 말레"),
    sp.ProductCandidate(2, "제주 힐링 효도 여행", "제주도"),
    sp.ProductCandidate(3, "한라산·올레길 트레킹", "제주도"),
    sp.ProductCandidate(4, "다낭 골프 챌린지", "베트남 다낭"),
]
CTX = sp.VoiceSelectionContext(None, None, PRODUCTS)


def interpret(text, ctx=CTX):
    return sp.interpret_selection(text, ctx)


class InterpreterTests(SimpleTestCase):
    def test_theme_and_level_filter(self):
        r = interpret("골프 그랜드 보여줘")
        self.assertEqual((r.status, r.kind, r.theme, r.tour_level),
                         (sp.VoiceSelectionStatus.SUCCESS, sp.VoiceSelectionKind.FILTER, "GOLF", "GRAND"))

    def test_only_said_fields_are_returned(self):
        r = interpret("프리미엄")
        self.assertEqual((r.theme, r.tour_level), (None, "PREMIUM"))
        self.assertEqual(interpret("신혼여행 상품").theme, "HONEYMOON")

    def test_product_by_unique_word(self):
        r = interpret("몰디브로 할게요")
        self.assertEqual((r.kind, r.product_id), (sp.VoiceSelectionKind.PRODUCT, 1))
        self.assertEqual(interpret("다낭").product_id, 4)

    def test_product_by_order(self):
        self.assertEqual(interpret("두 번째 상품").product_id, 2)
        self.assertEqual(interpret("3번").product_id, 3)
        self.assertEqual(interpret("9번").status, sp.VoiceSelectionStatus.UNSUPPORTED)

    def test_ambiguous_product_is_not_chosen(self):
        r = interpret("제주도")  # 제주 효도·한라산 트레킹 둘 다 해당
        self.assertEqual(r.status, sp.VoiceSelectionStatus.AMBIGUOUS)
        self.assertIsNone(r.product_id)

    def test_conflicting_themes_ambiguous(self):
        r = interpret("골프랑 트레킹")
        self.assertEqual(r.status, sp.VoiceSelectionStatus.AMBIGUOUS)
        self.assertIsNone(r.theme)

    def test_unsupported(self):
        r = interpret("호텔을 5성급으로 바꿔줘")
        self.assertEqual(r.status, sp.VoiceSelectionStatus.UNSUPPORTED)
        self.assertIsNone(r.kind)


class ManagerTests(SimpleTestCase):
    def test_empty_voice_is_recognition_failure(self):
        with self.assertLogs("apps.speech.services", "WARNING"):
            r = sp.recognize_selection(sp.VoiceInput(transcript="  "), CTX)
        self.assertEqual(r.status, sp.VoiceSelectionStatus.RECOGNITION_FAILED)

    def test_client_error_is_reported_not_raised(self):
        class Broken:
            def recognize(self, voice_input):
                raise ConnectionError("down")

        with self.assertLogs("apps.speech.services", "ERROR"):
            r = sp.recognize_selection(sp.VoiceInput(transcript="골프"), CTX, client=Broken())
        self.assertEqual(r.status, sp.VoiceSelectionStatus.RECOGNITION_FAILED)

    def test_recognized_text_included(self):
        r = sp.recognize_selection(sp.VoiceInput(transcript="트레킹 클래식"), CTX)
        self.assertEqual(r.recognized_text, "트레킹 클래식")
        self.assertEqual((r.theme, r.tour_level), ("TREKKING", "CLASSIC"))


class RecognizeViewTests(TestCase):
    def setUp(self):
        self.customer = Member.objects.create_user(username="c", password="x", name="고객", contact="010")
        day = timezone.localdate() + timedelta(days=10)
        for name, dest, theme in (("다낭 골프 챌린지", "베트남 다낭", "GOLF"), ("치앙마이 골프 챌린지", "태국 치앙마이", "GOLF"),
                                  ("한라산 트레킹", "제주도", "TREKKING")):
            p = TravelProduct(name=name, destination=dest, theme=theme, tour_level="CLASSIC", base_price=100_000,
                              travel_days=3, departure_date=day)
            p.apply_default_configuration()
            p.save()

    def post(self, transcript, **ctx):
        return self.client.post(reverse("speech:recognize"), {"transcript": transcript, **ctx},
                                content_type="application/json")

    def test_product_chosen_from_current_list_only(self):
        self.client.force_login(self.customer)
        r = self.post("치앙마이", theme="GOLF").json()
        self.assertEqual(r["kind"], "PRODUCT")
        self.assertEqual(r["product_id"], TravelProduct.objects.get(name="치앙마이 골프 챌린지").pk)
        # 현재 목록(골프)에 없는 상품은 고르지 않음
        self.assertEqual(self.post("한라산", theme="GOLF").json()["status"], "UNSUPPORTED")
        # 순서는 화면과 같은 목록 기준
        self.assertEqual(self.post("두 번째", theme="GOLF").json()["product_id"],
                         TravelProduct.objects.get(name="치앙마이 골프 챌린지").pk)

    def test_customer_only(self):
        staff = Member.objects.create_user(username="s", password="x", name="직원", contact="010",
                                           role=Member.Role.STAFF)
        self.client.force_login(staff)
        self.assertEqual(self.post("골프").status_code, 403)
