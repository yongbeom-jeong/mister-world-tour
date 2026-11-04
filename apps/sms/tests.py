from datetime import date

from django.test import SimpleTestCase

from . import services as sms


class FlakyClient:
    """처음 n번은 명확한 실패, 이후 성공."""

    def __init__(self, failures: int):
        self.failures = failures
        self.calls = 0

    def send_sms(self, contact, message):
        self.calls += 1
        if self.calls <= self.failures:
            raise sms.SMSSendError("일시 오류")


class UnknownClient:
    def __init__(self):
        self.calls = 0

    def send_sms(self, contact, message):
        self.calls += 1
        raise sms.SMSUnknownResult("응답 시간 초과")


NOTICE = sms.DepartureNotice(1, "다낭 골프 챌린지", date(2026, 12, 1))
ALICE = sms.SMSRecipient(1, "앨리스", "010-1000-0001")


class SendDepartureConfirmationTests(SimpleTestCase):
    def test_message_contains_product_and_date(self):
        text = NOTICE.message(ALICE)
        self.assertIn("다낭 골프 챌린지", text)
        self.assertIn("2026-12-01", text)

    def test_retries_until_success(self):
        client = FlakyClient(failures=3)
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation([ALICE], NOTICE, client=client)
        self.assertEqual(client.calls, 4)  # 최초 + 재시도 3회
        self.assertEqual(result.success, 1)

    def test_gives_up_after_four_attempts(self):
        client = FlakyClient(failures=10)
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation([ALICE], NOTICE, client=client)
        self.assertEqual(client.calls, 4)
        self.assertEqual(result.failed, 1)
        self.assertIn("실패 1건", result.summary())

    def test_unknown_result_is_not_retried(self):
        client = UnknownClient()
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation([ALICE], NOTICE, client=client)
        self.assertEqual(client.calls, 1)  # 중복 문자 방지
        self.assertEqual(result.unknown, 1)

    def test_missing_contact_skipped_others_sent(self):
        client = FlakyClient(failures=0)
        bob = sms.SMSRecipient(2, "밥", "")
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation([bob, ALICE], NOTICE, client=client)
        self.assertEqual(client.calls, 1)
        self.assertEqual((result.success, result.failed), (1, 1))
