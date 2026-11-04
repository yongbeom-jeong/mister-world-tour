"""UC6·UC7 직원 상품 등록·관리."""

from datetime import timedelta

from django.test import TestCase
from django.urls import reverse
from django.utils import timezone

from apps.members.models import Member

from . import services
from .choices import DepartureStatus, HotelLevel, MealOption
from .models import TravelApplication, TravelProduct


class StaffProductTests(TestCase):
    def setUp(self):
        self.staff = Member.objects.create_user(username="emp", password="x", name="직원", contact="010",
                                                role=Member.Role.STAFF)
        self.customer = Member.objects.create_user(username="cus", password="x", name="고객", contact="010")
        self.client.force_login(self.staff)
        self.tomorrow = timezone.localdate() + timedelta(days=1)

    def data(self, **kw):
        d = dict(name="새 상품", destination="괌", description="", theme="GOLF", tour_level="CLASSIC",
                 base_price="1000000", travel_days="4", departure_date=self.tomorrow.isoformat())
        d.update(kw)
        return d

    def product(self, **kw):
        d = self.data(**kw)
        d.update(base_price=int(d["base_price"]), travel_days=int(d["travel_days"]), departure_date=self.tomorrow)
        return services.register_tour_product(d)

    def apply(self, product):
        return TravelApplication.objects.create(
            member=self.customer, product=product, hotel_level=product.hotel_level, transport=product.transport,
            meal=product.meal, unit_price=1, subtotal=1, final_amount=1, request_key=f"k{product.pk}",
        )

    # ---------------------------------------------------------------- 등록 (UC6)

    def test_register_sets_default_configuration_and_souvenir(self):
        res = self.client.post(reverse("travel:product_register"), self.data())
        p = TravelProduct.objects.get(name="새 상품")
        self.assertRedirects(res, reverse("travel:product_manage_detail", args=[p.pk]))
        self.assertEqual((p.hotel_level, p.meal), (HotelLevel.STAR3, MealOption.LUNCHBOX))
        self.assertEqual(p.souvenir.name, "골프공")
        self.assertEqual(p.departure_status, DepartureStatus.PENDING)

    def test_register_validation(self):
        cases = {
            "tour_level": self.data(theme="HONEYMOON", tour_level="CLASSIC"),
            "departure_date": self.data(departure_date=timezone.localdate().isoformat()),
            "base_price": self.data(base_price="500"),
            "travel_days": self.data(travel_days="0"),
            "name": self.data(name=""),
            "destination": self.data(destination=""),
        }
        for field, d in cases.items():
            with self.subTest(field):
                res = self.client.post(reverse("travel:product_register"), d)
                self.assertEqual(res.status_code, 200)
                self.assertIn(field, res.context["form"].errors)
        self.assertFalse(TravelProduct.objects.exists())

    def test_customer_cannot_use_staff_pages(self):
        self.client.force_login(self.customer)
        for url in (reverse("travel:product_register"), reverse("travel:product_manage"),
                    reverse("travel:default_configuration") + "?theme=GOLF&tour_level=GRAND"):
            with self.subTest(url):
                self.assertEqual(self.client.get(url).status_code, 403)

    def test_default_configuration_endpoint(self):
        url = reverse("travel:default_configuration")
        data = self.client.get(url, {"theme": "HONEYMOON", "tour_level": "PREMIUM"}).json()
        self.assertEqual(data["configuration"]["hotel_level"], "5등급 호텔")
        self.assertEqual(data["configuration"]["souvenir"], "커플 기념티")
        self.assertEqual(self.client.get(url, {"theme": "X", "tour_level": "GRAND"}).status_code, 400)

    # ---------------------------------------------------------------- 관리 (UC7)

    def test_selecting_does_not_change_anything(self):
        p = self.product()
        res = self.client.get(reverse("travel:product_manage_detail", args=[p.pk]))
        self.assertEqual(res.context["selected"].pk, p.pk)
        self.assertEqual(TravelProduct.objects.get(pk=p.pk).name, "새 상품")

    def test_manage_page_has_unique_field_ids(self):
        """목록 필터와 상품 입력 폼이 같은 id 를 쓰면 기본 구성 미리보기가 엉뚱한 값을 읽는다."""
        p = self.product()
        html = self.client.get(reverse("travel:product_manage_detail", args=[p.pk])).content.decode()
        for field_id in ("id_theme", "id_tour_level"):
            self.assertEqual(html.count(f'id="{field_id}"'), 1, field_id)

    def test_register_with_photo_and_remove_it(self):
        import io

        from PIL import Image
        from django.core.files.uploadedfile import SimpleUploadedFile

        buf = io.BytesIO()
        Image.new("RGB", (2400, 1200), "navy").save(buf, "PNG")
        photo = SimpleUploadedFile("big.png", buf.getvalue(), content_type="image/png")
        self.client.post(reverse("travel:product_register"), {**self.data(), "image": photo})
        p = TravelProduct.objects.get(name="새 상품")
        self.assertTrue(p.image.name.endswith(".jpg"))
        with Image.open(p.image.path) as im:
            self.assertEqual(max(im.size), 1600)  # 큰 사진은 1600px 로 줄임

        self.client.post(reverse("travel:product_manage_detail", args=[p.pk]), {**self.data(), "image-clear": "on"})
        p.refresh_from_db()
        self.assertFalse(p.image)  # 테마 기본 사진 사용

    def test_non_image_upload_rejected(self):
        from django.core.files.uploadedfile import SimpleUploadedFile

        fake = SimpleUploadedFile("x.jpg", b"not an image", content_type="image/jpeg")
        res = self.client.post(reverse("travel:product_register"), {**self.data(), "image": fake})
        self.assertIn("image", res.context["form"].errors)
        self.assertFalse(TravelProduct.objects.exists())

    def test_modify_regenerates_default_configuration(self):
        p = self.product()
        self.client.post(reverse("travel:product_manage_detail", args=[p.pk]),
                         self.data(name="바뀐 상품", theme="HONEYMOON", tour_level="PREMIUM"))
        p.refresh_from_db()
        self.assertEqual(p.name, "바뀐 상품")
        self.assertEqual((p.hotel_level, p.transport), (HotelLevel.STAR5, "LUXURY_CAR_2"))
        self.assertIsNone(p.departure_status)  # 허니문은 상품 공동 상태 없음
        self.assertEqual(p.souvenir.name, "커플 기념티")

    def test_modify_validation_error_keeps_saved_product(self):
        p = self.product()
        res = self.client.post(reverse("travel:product_manage_detail", args=[p.pk]), self.data(name="", base_price="1"))
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.context["selected"].name, "새 상품")  # 표시용 원본은 입력값으로 덮이지 않음
        p.refresh_from_db()
        self.assertEqual(p.name, "새 상품")

    def test_modify_and_delete_blocked_when_applied(self):
        p = self.product()
        self.apply(p)
        self.client.post(reverse("travel:product_manage_detail", args=[p.pk]), self.data(name="바꾸기"))
        self.client.post(reverse("travel:product_delete", args=[p.pk]))
        p.refresh_from_db()
        self.assertEqual(p.name, "새 상품")
        res = self.client.get(reverse("travel:product_manage_detail", args=[p.pk]))
        self.assertTrue(res.context["has_applications"])

    def test_delete(self):
        p = self.product()
        res = self.client.post(reverse("travel:product_delete", args=[p.pk]))
        self.assertRedirects(res, reverse("travel:product_manage"))
        self.assertFalse(TravelProduct.objects.filter(pk=p.pk).exists())
        self.assertEqual(self.client.get(reverse("travel:product_delete", args=[p.pk])).status_code, 405)

    def test_missing_product_redirects(self):
        res = self.client.get(reverse("travel:product_manage_detail", args=[9999]))
        self.assertRedirects(res, reverse("travel:product_manage"))

    def test_list_filter(self):
        self.product(name="골프1")
        self.product(name="트레킹1", theme="TREKKING")
        res = self.client.get(reverse("travel:product_manage"), {"theme": "TREKKING"})
        self.assertEqual([p.name for p in res.context["products"]], ["트레킹1"])
