import hashlib
import hmac
import io
import json
import socket
import urllib.error
from datetime import date
from unittest import mock

from django.core.exceptions import ImproperlyConfigured
from django.test import SimpleTestCase, override_settings

from . import services as sms
from . import solapi


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
        self.assertEqual(text, "[미스터월드투어] 앨리스님, 다낭 골프 챌린지 12월 1일 출발 확정되었습니다.")
        self.assertLessEqual(sms.sms_bytes(text), sms.SMS_MAX_BYTES)

    def test_long_names_still_fit_in_short_message(self):
        long_name = sms.SMSRecipient(3, "가" * 30, "010-1000-0003")
        text = NOTICE.message(long_name)  # 이름을 빼고 줄인 문구
        self.assertEqual(text, "[미스터월드투어] 다낭 골프 챌린지 12월 1일 출발 확정")
        long_product = sms.DepartureNotice(2, "안나푸르나 베이스캠프 " * 6, date(2026, 12, 31))
        text = long_product.message(ALICE)
        self.assertLessEqual(sms.sms_bytes(text), sms.SMS_MAX_BYTES)
        self.assertTrue(text.startswith("[미스터월드투어] 안나푸르나"))
        self.assertTrue(text.endswith(".. 12월 31일 출발 확정"))

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

    def test_rejected_number_is_not_retried(self):
        class RejectClient:
            calls = 0

            def send_sms(self, contact, message):
                RejectClient.calls += 1
                raise sms.SMSRejected("허용 목록 밖")

        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation([ALICE], NOTICE, client=RejectClient())
        self.assertEqual(RejectClient.calls, 1)
        self.assertEqual(result.failed, 1)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class SlowClient:
    """호출마다 timeout 초를 다 쓰고 결과 불명 (응답 시간 초과 상황)."""

    def __init__(self, clock, timeout=10, error=sms.SMSUnknownResult):
        self.clock, self.timeout, self.error = clock, timeout, error
        self.calls = []

    def send_sms(self, contact, message):
        self.calls.append(contact)
        self.clock.now += self.timeout
        raise self.error("느림")


@override_settings(SMS_BATCH_TIME_LIMIT=40)
class BatchTimeLimitTests(SimpleTestCase):
    def recipients(self, n):
        return [sms.SMSRecipient(i, f"고객{i}", f"010-1000-{i:04d}") for i in range(n)]

    def test_stops_before_exceeding_limit(self):
        clock = FakeClock()
        client = SlowClient(clock)
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation(self.recipients(10), NOTICE, client=client, clock=clock)
        self.assertEqual(len(client.calls), 4)  # 10초 × 4 = 40초까지만
        self.assertLessEqual(clock.now, 40)
        self.assertEqual(result.total, 10)
        self.assertEqual((result.unknown, result.failed), (4, 6))
        skipped = [m for m in result.messages if m.attempt_count == 0]
        self.assertEqual(len(skipped), 6)
        self.assertIn("시간 한도", skipped[0].last_error)

    def test_retries_also_respect_limit(self):
        clock = FakeClock()
        client = SlowClient(clock, timeout=15, error=sms.SMSSendError)
        with self.assertLogs("apps.sms.services", "WARNING"):
            result = sms.send_departure_confirmation(self.recipients(1), NOTICE, client=client, clock=clock)
        self.assertEqual(len(client.calls), 2)  # 15 + 15 = 30, 세 번째는 45초라 시도 안 함
        self.assertEqual(result.messages[0].attempt_count, 2)
        self.assertEqual(result.failed, 1)

    def test_fast_client_sends_all(self):
        client = FlakyClient(failures=0)  # timeout 속성 없음 = 대기 0
        result = sms.send_departure_confirmation(self.recipients(10), NOTICE, client=client)
        self.assertEqual(result.success, 10)


# ---------------------------------------------------------------- 솔라피 클라이언트 (실제 네트워크 호출 없음)

SOLAPI = dict(SOLAPI_API_KEY="KEY", SOLAPI_API_SECRET="SECRET", SOLAPI_SENDER="010-9999-0000",
              SMS_ALLOWED_NUMBERS=[])


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def ok_response(failed=None):
    body = {"groupInfo": {"groupId": "G1"}, "failedMessageList": failed or []}
    return FakeResponse(json.dumps(body).encode())


@override_settings(**SOLAPI)
class SolapiClientTests(SimpleTestCase):
    def test_auth_header_signature(self):
        header = solapi.auth_header("KEY", "SECRET", date="2026-11-11T00:00:00Z", salt="abc")
        expected = hmac.new(b"SECRET", b"2026-11-11T00:00:00Zabc", hashlib.sha256).hexdigest()
        self.assertEqual(header, f"HMAC-SHA256 apiKey=KEY, date=2026-11-11T00:00:00Z, salt=abc, signature={expected}")

    def test_sends_digits_only_numbers(self):
        with mock.patch("urllib.request.urlopen", return_value=ok_response()) as urlopen:
            solapi.SolapiSMSClient().send_sms("010-1000-0001", "안녕")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, solapi.API_URL)
        self.assertTrue(request.get_header("Authorization").startswith("HMAC-SHA256 apiKey=KEY, "))
        body = json.loads(request.data)
        self.assertEqual(body, {"messages": [{"to": "01010000001", "from": "01099990000", "text": "안녕"}]})

    def test_failed_message_list_is_send_error(self):
        failed = [{"statusCode": "3040", "statusMessage": "등록되지 않은 발신번호입니다."}]
        with mock.patch("urllib.request.urlopen", return_value=ok_response(failed)):
            with self.assertRaisesMessage(sms.SMSSendError, "3040"):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")

    def test_http_error_is_send_error(self):
        err = urllib.error.HTTPError(solapi.API_URL, 401, "Unauthorized", {}, io.BytesIO(b'{"errorCode":"x"}'))
        with mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaises(sms.SMSSendError):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")

    def test_server_error_is_unknown(self):
        err = urllib.error.HTTPError(solapi.API_URL, 502, "Bad Gateway", {}, io.BytesIO(b""))
        with mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaises(sms.SMSUnknownResult):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")

    def test_timeout_is_unknown(self):
        with mock.patch("urllib.request.urlopen", side_effect=socket.timeout("timed out")):
            with self.assertRaises(sms.SMSUnknownResult):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError(TimeoutError())):
            with self.assertRaises(sms.SMSUnknownResult):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")

    def test_connection_error_is_send_error(self):
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("refused")):
            with self.assertRaises(sms.SMSSendError):
                solapi.SolapiSMSClient().send_sms("01010000001", "안녕")

    @override_settings(SMS_ALLOWED_NUMBERS=["010-1111-2222"])
    def test_allowed_numbers_block_others_without_network(self):
        with mock.patch("urllib.request.urlopen", return_value=ok_response()) as urlopen:
            with self.assertRaises(sms.SMSRejected):
                solapi.SolapiSMSClient().send_sms("010-1000-0001", "안녕")
            urlopen.assert_not_called()
            solapi.SolapiSMSClient().send_sms("01011112222", "안녕")
            urlopen.assert_called_once()

    @override_settings(SOLAPI_API_SECRET="")
    def test_missing_settings(self):
        with self.assertRaisesMessage(ImproperlyConfigured, "SOLAPI_API_SECRET"):
            solapi.SolapiSMSClient()
