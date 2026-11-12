"""다녀온 여행을 완료 이력으로 만든다 (서버에서 하루 한 번 실행 권장).

이력 조회·신청 금액 계산 때 그 고객의 이력은 자동으로 반영되므로, 이 명령은 전체를 한꺼번에 맞출 때 쓴다.
    python manage.py complete_trips
"""

from django.core.management.base import BaseCommand

from apps.travel.services import complete_finished_trips


class Command(BaseCommand):
    help = "여행 마지막 날이 지난 출발 확정 신청을 완료 여행 이력으로 만든다."

    def handle(self, *args, **options):
        n = complete_finished_trips()
        self.stdout.write(self.style.SUCCESS(f"완료 여행 이력 {n}건 생성"))
