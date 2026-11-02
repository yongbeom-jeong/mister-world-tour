"""데모·시험용 더미 데이터 생성.

    python manage.py seed_demo

여러 번 실행해도 같은 데이터가 중복 생성되지 않는다(아이디·상품명·요청 키 기준).
날짜는 실행일 기준 상대값이라 언제 실행해도 단골 판정과 출발일이 의미 있게 유지된다.
모든 값은 설계에 정해지지 않은 임의 값이다.
"""

from datetime import date, timedelta

from django.conf import settings
from django.core.files import File
from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from apps.members.models import Member
from apps.souvenir.models import Souvenir, StockReceipt
from apps.souvenir.services import find_souvenir_for_theme
from apps.travel import booking
from apps.travel import configuration as cfg
from apps.travel.choices import Theme, TourLevel
from apps.travel.models import TravelHistory, TravelProduct

PASSWORD = "demo1234!"

MEMBERS = [
    # username, role, name, address, contact
    ("staff1", Member.Role.STAFF, "김직원", "서울시 중구 세종대로 110", "010-1000-0001"),
    ("regular1", Member.Role.CUSTOMER, "박단골", "서울시 강남구 테헤란로 1", "010-2000-0001"),
    ("customer2", Member.Role.CUSTOMER, "이두번", "부산시 해운대구 해운대로 2", "010-2000-0002"),
    ("newbie", Member.Role.CUSTOMER, "최처음", "대전시 유성구 대학로 3", "010-2000-0003"),
]

# name, destination, theme, level, 1인 가격, 기간(일), 오늘부터 출발까지 일수, 소개
PRODUCTS = [
    ("몰디브 워터빌라 허니문", "몰디브 말레", Theme.HONEYMOON, TourLevel.PREMIUM, 3_200_000, 6, 45,
     "수상 빌라에서 보내는 둘만의 6일. 선셋 크루즈 포함."),
    ("발리 우붓 로맨틱 허니문", "인도네시아 발리", Theme.HONEYMOON, TourLevel.GRAND, 2_400_000, 5, 30,
     "우붓 정글 풀빌라와 짐바란 해변 디너."),
    ("하와이 마우이 허니문", "미국 하와이", Theme.HONEYMOON, TourLevel.PREMIUM, 3_800_000, 7, 60,
     "마우이 할레아칼라 일출과 오아후 자유일정."),
    ("제주 힐링 효도 여행", "제주도", Theme.FILIAL, TourLevel.GRAND, 890_000, 3, 20,
     "천천히 걷는 일정과 매일 안마·지압 서비스."),
    ("벳푸 온천 효도 여행", "일본 오이타 벳푸", Theme.FILIAL, TourLevel.PREMIUM, 1_650_000, 4, 35,
     "료칸 숙박과 가이세키 요리, 지옥온천 순례."),
    ("타이베이 효도 여행", "대만 타이베이", Theme.FILIAL, TourLevel.GRAND, 1_190_000, 4, 50,
     "고궁박물원·스펀 천등 날리기, 발 마사지 포함."),
    ("치앙마이 골프 챌린지", "태국 치앙마이", Theme.GOLF, TourLevel.CLASSIC, 1_290_000, 5, 25,
     "명문 코스 3라운드, 카트·캐디피 포함."),
    ("다낭 골프 챌린지", "베트남 다낭", Theme.GOLF, TourLevel.GRAND, 1_490_000, 5, 40,
     "바다를 끼고 도는 몽고메리 링크스 2라운드."),
    ("미야자키 골프 챌린지", "일본 미야자키", Theme.GOLF, TourLevel.PREMIUM, 2_100_000, 4, 55,
     "피닉스 컨트리클럽 라운드와 온천 리조트."),
    ("안나푸르나 베이스캠프 트레킹", "네팔 포카라", Theme.TREKKING, TourLevel.CLASSIC, 2_300_000, 10, 70,
     "ABC 4,130m까지 전문 가이드와 함께."),
    ("융프라우 알프스 트레킹", "스위스 인터라켄", Theme.TREKKING, TourLevel.PREMIUM, 4_500_000, 8, 90,
     "아이거 트레일과 융프라우요흐, 산악열차 패스 포함."),
    ("한라산·올레길 트레킹", "제주도", Theme.TREKKING, TourLevel.CLASSIC, 590_000, 3, 15,
     "성판악 코스 정상 등반과 올레 7코스."),
]

# 데모 상품 사진 (static/img/photos/<이름>.jpg, 출처는 credits.json)
PRODUCT_PHOTOS = {
    "몰디브 워터빌라 허니문": "maldives", "발리 우붓 로맨틱 허니문": "bali", "하와이 마우이 허니문": "maui",
    "제주 힐링 효도 여행": "jeju", "벳푸 온천 효도 여행": "beppu", "타이베이 효도 여행": "taipei",
    "치앙마이 골프 챌린지": "chiangmai", "다낭 골프 챌린지": "danang", "미야자키 골프 챌린지": "miyazaki",
    "안나푸르나 베이스캠프 트레킹": "annapurna", "융프라우 알프스 트레킹": "jungfrau", "한라산·올레길 트레킹": "hallasan",
}

# 초기 기념품 재고 (입고 내역으로 기록)
STOCK = {"커플 기념티": 20, "건강 인삼": 30, "골프공": 50, "기념품 스카프": 40}

# username → [(상품명, 등급, 기간, 가격, 완료일까지 며칠 전, 완료 여부)]
HISTORIES = {
    # 최근 3년 내 완료 3회 → 단골(10% 할인)
    "regular1": [
        ("오사카 미식 효도 여행", TourLevel.GRAND, 4, 1_100_000, 120, True),
        ("괌 가족 휴양", TourLevel.GRAND, 5, 1_500_000, 400, True),
        ("다낭 골프 챌린지", TourLevel.CLASSIC, 5, 1_300_000, 800, True),
        ("푸껫 골프 챌린지", TourLevel.CLASSIC, 5, 1_200_000, 1_500, True),  # 3년 지남
    ],
    # 3년 내 2회 + 오래된 1회 → 단골 아님
    "customer2": [
        ("제주 힐링 효도 여행", TourLevel.GRAND, 3, 850_000, 200, True),
        ("한라산·올레길 트레킹", TourLevel.CLASSIC, 3, 550_000, 600, True),
        ("홋카이도 설경 트레킹", TourLevel.GRAND, 6, 1_900_000, 1_400, True),
    ],
}


# 미리 넣어 둘 신청: (아이디, 상품명, 동반 여행자 이름들)
# - 다낭 골프: 2명 → 누군가 1명만 더 신청하면 출발 확정 (확정 문자 시연)
# - 제주 효도: 8명 → 이미 확정, 남은 자리 2명 (정원 초과 시연)
APPLICATIONS = [
    ("customer2", "다낭 골프 챌린지", ["이동반"]),
    ("customer2", "제주 힐링 효도 여행", ["이부모", "이모친", "김이모", "김삼촌", "박사촌", "박조카", "최할매"]),
]


class Command(BaseCommand):
    help = "데모용 계정·상품·기념품 재고·여행 이력을 만든다."

    @transaction.atomic
    def handle(self, *args, **options):
        today = timezone.localdate()
        members = self._members()
        self._stock(members["staff1"])
        n_products = self._products(today)
        self._photos()
        n_hist = self._histories(members, today)
        n_apps = self._applications(members)

        self.stdout.write(self.style.SUCCESS("데모 데이터 준비 완료"))
        self.stdout.write(f"  상품 {n_products}개 신규, 여행 이력 {n_hist}건 신규, 신청 {n_apps}건 신규")
        self.stdout.write(f"  계정(비밀번호 모두 {PASSWORD}):")
        for username, role, name, *_ in MEMBERS:
            self.stdout.write(f"    {username:<10} {Member.Role(role).label}  {name}")

    def _members(self) -> dict[str, Member]:
        result = {}
        for username, role, name, address, contact in MEMBERS:
            m, created = Member.objects.get_or_create(
                username=username, defaults=dict(role=role, name=name, address=address, contact=contact)
            )
            if created:
                m.set_password(PASSWORD)
                m.save()
            result[username] = m
        return result

    def _stock(self, staff: Member) -> None:
        for name, qty in STOCK.items():
            s = Souvenir.objects.filter(name=name).first()
            if s is None:
                continue
            key = f"seed-{s.pk}"
            if StockReceipt.objects.filter(request_key=key).exists():
                continue
            s.stock_quantity += qty
            s.save(update_fields=["stock_quantity"])
            StockReceipt.objects.create(
                souvenir=s, employee=staff, quantity=qty, received_at=timezone.now(),
                request_key=key, stock_after=s.stock_quantity,
            )

    def _products(self, today: date) -> int:
        created = 0
        for name, dest, theme, level, price, days, offset, desc in PRODUCTS:
            if TravelProduct.objects.filter(name=name).exists():
                continue
            p = TravelProduct(
                name=name, destination=dest, description=desc, theme=theme, tour_level=level,
                base_price=price, travel_days=days, departure_date=today + timedelta(days=offset),
                souvenir=find_souvenir_for_theme(theme),
            )
            p.apply_default_configuration()
            p.full_clean()
            p.save()
            created += 1
        return created

    def _histories(self, members: dict[str, Member], today: date) -> int:
        created = 0
        for username, rows in HISTORIES.items():
            for i, (pname, level, days, price, ago, done) in enumerate(rows):
                _, c = TravelHistory.objects.get_or_create(
                    member=members[username], completed_travel_id=f"seed-{username}-{i}",
                    defaults=dict(product_name=pname, tour_level=level, travel_days=days, price=price,
                                  completed=done, completed_at=today - timedelta(days=ago) if done else None),
                )
                created += c
        return created

    def _applications(self, members: dict[str, Member]) -> int:
        """실제 신청 처리(검토 → 등록)를 그대로 사용한다. 요청 키를 고정해 다시 실행해도 중복되지 않는다."""
        created = 0
        for i, (username, product_name, companions) in enumerate(APPLICATIONS):
            member = members[username]
            product = TravelProduct.objects.filter(name=product_name).first()
            if product is None:
                continue
            key = f"seed-application-{i}"
            if product.applications.filter(request_key=key).exists():
                continue
            rows = [{"name": member.name, "contact": member.contact}] + [{"name": n, "contact": ""} for n in companions]
            review = booking.review_application(member, product.pk, cfg.default_configuration(product), rows)
            review.request_key = key
            booking.submit_travel_application(member, review)
            created += 1
        return created

    def _photos(self) -> None:
        """사진이 없는 데모 상품에 기본 사진을 업로드 파일처럼 넣는다 (직원이 올린 사진은 건드리지 않음)."""
        photo_dir = settings.BASE_DIR / "static" / "img" / "photos"
        for name, photo in PRODUCT_PHOTOS.items():
            p = TravelProduct.objects.filter(name=name).first()
            if p is None or p.image:
                continue
            with open(photo_dir / f"{photo}.jpg", "rb") as f:
                p.image.save(f"{photo}.jpg", File(f), save=True)
