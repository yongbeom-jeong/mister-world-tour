"""계획서 평가 항목 측정 (seed_demo 데이터 기준).

1. 음성 명령 20개: 계획서 목표 18개 이상 성공.
   여기서는 브라우저가 인식한 문장이 정확하다고 보고 서버 해석(/speech/recognize/)만 확인한다.
   실제 마이크 측정은 같은 목록을 시연 PC 에서 말해 보고 기록한다 (--list 로 목록만 출력).
2. 상품 목록·이력 조회 각 10회: 계획서 목표 평균 2초 이내.
   서버 처리 시간(Django 테스트 클라이언트)이다. 네트워크·브라우저 렌더링 시간은 포함하지 않는다.

사용 예
    python manage.py seed_demo
    python manage.py measure_evaluation
    python manage.py measure_evaluation --list
    python manage.py measure_evaluation --csv 측정결과.csv
"""

import csv
import json
import statistics
import time

from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from django.test import Client
from django.urls import reverse

from apps.members.models import Member
from apps.travel.choices import Theme, TourLevel
from apps.travel.models import TravelProduct

CUSTOMER = "regular1"  # seed_demo 단골 고객 (이력이 있어 이력 조회 측정에 적합)
REPEAT = 10
VOICE_TARGET = 18
TIME_TARGET = 2.0

# (화면 테마, 화면 등급, 말한 문장, 기대: ("FILTER", 테마, 등급) 또는 ("PRODUCT", 상품 이름))
VOICE_COMMANDS = [
    (None, None, "골프 상품 보여줘", ("FILTER", "GOLF", None)),
    (None, None, "허니문 프리미엄", ("FILTER", "HONEYMOON", "PREMIUM")),
    (None, None, "신혼여행 찾아줘", ("FILTER", "HONEYMOON", None)),
    (None, None, "부모님 모시고 갈 여행", ("FILTER", "FILIAL", None)),
    (None, None, "등산 여행 보여줘", ("FILTER", "TREKKING", None)),
    (None, None, "트레킹 클래식", ("FILTER", "TREKKING", "CLASSIC")),
    (None, None, "그랜드 등급으로", ("FILTER", None, "GRAND")),
    (None, None, "효도 그랜드", ("FILTER", "FILIAL", "GRAND")),
    (None, None, "프리미엄 골프", ("FILTER", "GOLF", "PREMIUM")),
    (None, None, "실속형 상품", ("FILTER", None, "CLASSIC")),
    ("HONEYMOON", None, "몰디브", ("PRODUCT", "몰디브 워터빌라 허니문")),
    ("HONEYMOON", None, "발리로 갈래", ("PRODUCT", "발리 우붓 로맨틱 허니문")),
    ("HONEYMOON", None, "하와이 마우이", ("PRODUCT", "하와이 마우이 허니문")),
    ("GOLF", None, "다낭 골프 챌린지", ("PRODUCT", "다낭 골프 챌린지")),
    ("GOLF", None, "치앙마이 선택", ("PRODUCT", "치앙마이 골프 챌린지")),
    ("TREKKING", None, "융프라우", ("PRODUCT", "융프라우 알프스 트레킹")),
    ("FILIAL", None, "벳푸 온천", ("PRODUCT", "벳푸 온천 효도 여행")),
    # 순서 선택은 화면 목록(출발일 순) 기준. seed_demo 를 새로 만든 DB 에서의 순서다.
    ("GOLF", None, "두 번째 상품", ("PRODUCT", "다낭 골프 챌린지")),
    ("HONEYMOON", None, "첫 번째", ("PRODUCT", "발리 우붓 로맨틱 허니문")),
    ("FILIAL", None, "3번", ("PRODUCT", "타이베이 효도 여행")),
]


def _host() -> str:
    """ALLOWED_HOSTS 를 통과하는 호스트 이름 (테스트 클라이언트 요청용)."""
    for host in getattr(settings, "ALLOWED_HOSTS", []):
        host = host.lstrip(".")
        if host and host != "*":
            return host
    return "localhost"


def _screen(theme, level) -> str:
    labels = [Theme(theme).label] if theme else []
    if level:
        labels.append(TourLevel(level).label)
    return " ".join(labels) or "전체"


def _expected_text(expected) -> str:
    if expected[0] == "PRODUCT":
        return f"상품 '{expected[1]}'"
    return "조건 " + _screen(expected[1], expected[2])


class Command(BaseCommand):
    help = "계획서 평가 항목(음성 명령 20개, 목록·이력 조회 응답 시간)을 측정한다."

    def add_arguments(self, parser):
        parser.add_argument("--list", action="store_true", help="음성 명령 목록만 출력 (마이크 측정용)")
        parser.add_argument("--csv", help="결과를 CSV 파일로 저장 (엑셀에서 열 수 있게 UTF-8 BOM)")

    def handle(self, *args, **options):
        if options["list"]:
            self._print_list()
            return
        member = Member.objects.filter(username=CUSTOMER).first()
        if member is None:
            raise CommandError(f"'{CUSTOMER}' 계정이 없습니다. 먼저 'python manage.py seed_demo' 를 실행하세요.")

        client = Client(HTTP_HOST=_host())
        client.force_login(member)
        rows = []
        voice_ok = self._measure_voice(client, rows)
        timing_ok = self._measure_timing(client, rows)

        if options["csv"]:
            with open(options["csv"], "w", newline="", encoding="utf-8-sig") as f:
                csv.writer(f).writerows([("항목", "입력", "기대", "결과", "판정")] + rows)
            self.stdout.write(f"\nCSV 저장: {options['csv']}")
        style = self.style.SUCCESS if voice_ok and timing_ok else self.style.ERROR
        self.stdout.write(style("\n전체: " + ("목표 달성" if voice_ok and timing_ok else "목표 미달 항목 있음")))

    def _print_list(self):
        self.stdout.write("음성 명령 측정 목록 (화면 조건을 맞춘 뒤 마이크로 말하고 결과를 기록)")
        for i, (theme, level, text, expected) in enumerate(VOICE_COMMANDS, 1):
            self.stdout.write(f"{i:>2}. [화면: {_screen(theme, level)}] \"{text}\" → {_expected_text(expected)}")

    # ------------------------------------------------------------ 음성 명령
    def _measure_voice(self, client, rows) -> bool:
        self.stdout.write(f"음성 명령 해석 {len(VOICE_COMMANDS)}개 (목표 {VOICE_TARGET}개 이상)")
        names = dict(TravelProduct.objects.values_list("pk", "name"))
        passed = 0
        for i, (theme, level, text, expected) in enumerate(VOICE_COMMANDS, 1):
            res = client.post(reverse("speech:recognize"), json.dumps(
                {"transcript": text, "theme": theme, "tour_level": level}), content_type="application/json",
                secure=True)
            data = res.json() if res.status_code == 200 else {"status": f"HTTP {res.status_code}", "message": ""}
            if data.get("kind") == "PRODUCT":
                got = ("PRODUCT", names.get(data.get("product_id")))
                got_text = f"상품 '{got[1]}'"
            elif data.get("kind") == "FILTER":
                got = ("FILTER", data.get("theme"), data.get("tour_level"))
                got_text = "조건 " + _screen(got[1], got[2])
            else:
                got = None
                got_text = f"{data.get('status')}: {data.get('message')}"
            ok = data.get("status") == "SUCCESS" and got == tuple(expected)
            passed += ok
            mark = "성공" if ok else "실패"
            self.stdout.write(f"  {i:>2}. {mark} [화면: {_screen(theme, level)}] \"{text}\" → {got_text}")
            rows.append(("음성", f"[{_screen(theme, level)}] {text}", _expected_text(expected), got_text, mark))
        ok = passed >= VOICE_TARGET
        style = self.style.SUCCESS if ok else self.style.ERROR
        self.stdout.write(style(f"  결과: {passed}/{len(VOICE_COMMANDS)} 성공"))
        return ok

    # ------------------------------------------------------------ 응답 시간
    def _measure_timing(self, client, rows) -> bool:
        self.stdout.write(f"\n조회 응답 시간 각 {REPEAT}회 (목표 평균 {TIME_TARGET:.0f}초 이내, 서버 처리 시간)")
        all_ok = True
        for label, url in (("상품 목록", reverse("travel:products")), ("여행 이력", reverse("travel:history"))):
            times = []
            for _ in range(REPEAT):
                start = time.perf_counter()
                res = client.get(url, secure=True)
                times.append(time.perf_counter() - start)
                if res.status_code != 200:
                    raise CommandError(f"{label} 조회 실패: HTTP {res.status_code} ({url})")
            avg, worst = statistics.mean(times), max(times)
            ok = avg <= TIME_TARGET
            all_ok &= ok
            mark = "달성" if ok else "미달"
            style = self.style.SUCCESS if ok else self.style.ERROR
            self.stdout.write(style(f"  {label}: 평균 {avg * 1000:.1f}ms, 최대 {worst * 1000:.1f}ms → {mark}"))
            rows.append(("응답 시간", f"{label} {url} × {REPEAT}", f"평균 {TIME_TARGET:.0f}초 이내",
                         f"평균 {avg * 1000:.1f}ms / 최대 {worst * 1000:.1f}ms", mark))
        return all_ok
