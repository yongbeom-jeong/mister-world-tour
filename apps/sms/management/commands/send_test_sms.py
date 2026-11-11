"""문자 연동 확인용 시험 발송.

    python manage.py send_test_sms 010-1234-5678
    python manage.py send_test_sms 010-1234-5678 --text "보낼 내용"

.env 의 SMS_CLIENT 로 설정한 클라이언트를 그대로 사용한다 (재시도 없이 1회).
"""

from django.core.management.base import BaseCommand, CommandError

from apps.sms import services as sms


class Command(BaseCommand):
    help = "설정된 문자 클라이언트로 시험 문자를 1통 보낸다."

    def add_arguments(self, parser):
        parser.add_argument("contact", help="받을 휴대전화 번호 (예: 010-1234-5678)")
        parser.add_argument("--text", default="[미스터 월드 투어] 문자 발송 시험입니다.")

    def handle(self, *args, contact, text, **options):
        client = sms.get_client()
        self.stdout.write(f"클라이언트: {type(client).__name__} → {contact}")
        try:
            client.send_sms(contact, text)
        except sms.SMSUnknownResult as e:
            raise CommandError(f"결과 불명: {e} (휴대폰에서 수신 여부를 확인하세요)")
        except Exception as e:
            raise CommandError(f"발송 실패: {e}")
        self.stdout.write(self.style.SUCCESS("발송 요청 성공"))
