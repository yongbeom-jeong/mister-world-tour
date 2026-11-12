"""솔라피(SOLAPI) 문자 발송 클라이언트.

.env 설정:
    SMS_CLIENT=apps.sms.solapi.SolapiSMSClient
    SOLAPI_API_KEY=...        # 솔라피 콘솔 > API Key 관리
    SOLAPI_API_SECRET=...
    SOLAPI_SENDER=01012345678 # 솔라피에 등록·인증된 발신번호
    SMS_ALLOWED_NUMBERS=010-1111-2222,010-3333-4444  # (선택) 이 번호로만 실제 발송

- 인증: HMAC-SHA256 (서명 대상 = date + salt, 키 = API Secret, 16진수 서명)
- 발송: POST /messages/v4/send-many/detail. 90byte 를 넘으면 솔라피가 자동으로 LMS 로 보낸다.
- 결과 구분 (services.send_departure_confirmation 의 재시도 규칙에 맞춤)
    · 연결 실패·HTTP 4xx·접수 거부(failedMessageList) → SMSSendError (재시도)
    · 응답 시간 초과·HTTP 5xx·응답 해석 불가 → SMSUnknownResult (중복 문자 방지를 위해 재시도 안 함)
    · 허용 목록 밖 번호 → SMSRejected (외부 호출 없이 실패, 재시도 안 함)
"""

import hashlib
import hmac
import json
import logging
import re
import secrets
import socket
import urllib.error
import urllib.request
from datetime import datetime, timezone

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured

from .services import SMSRejected, SMSSendError, SMSUnknownResult

logger = logging.getLogger(__name__)

API_URL = "https://api.solapi.com/messages/v4/send-many/detail"


def digits_only(number: str) -> str:
    return re.sub(r"\D", "", number or "")


def auth_header(api_key: str, api_secret: str, date: str | None = None, salt: str | None = None) -> str:
    date = date or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    salt = salt or secrets.token_hex(16)
    signature = hmac.new(api_secret.encode(), (date + salt).encode(), hashlib.sha256).hexdigest()
    return f"HMAC-SHA256 apiKey={api_key}, date={date}, salt={salt}, signature={signature}"


class SolapiSMSClient:
    def __init__(self):
        self.api_key = getattr(settings, "SOLAPI_API_KEY", "")
        self.api_secret = getattr(settings, "SOLAPI_API_SECRET", "")
        self.sender = digits_only(getattr(settings, "SOLAPI_SENDER", ""))
        self.timeout = getattr(settings, "SMS_TIMEOUT", 10)
        self.allowed = {digits_only(n) for n in getattr(settings, "SMS_ALLOWED_NUMBERS", []) if digits_only(n)}
        missing = [name for name, value in (("SOLAPI_API_KEY", self.api_key), ("SOLAPI_API_SECRET", self.api_secret),
                                            ("SOLAPI_SENDER", self.sender)) if not value]
        if missing:
            raise ImproperlyConfigured(f"솔라피 문자 설정 누락: {', '.join(missing)} (.env 확인)")

    def send_sms(self, contact: str, message: str) -> str:
        """접수되면 솔라피 groupId 를 돌려준다 (발송 기록의 업체 접수 번호)."""
        to = digits_only(contact)
        if self.allowed and to not in self.allowed:
            raise SMSRejected(f"SMS_ALLOWED_NUMBERS 에 없는 번호라 보내지 않음 ({contact})")

        body = json.dumps({"messages": [{"to": to, "from": self.sender, "text": message}]}).encode()
        request = urllib.request.Request(API_URL, data=body, method="POST", headers={
            "Authorization": auth_header(self.api_key, self.api_secret),
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code >= 500:  # 서버·게이트웨이 오류: 실제로는 접수됐을 수 있어 다시 보내지 않음
                raise SMSUnknownResult(f"솔라피 HTTP {e.code}: {detail}") from e
            raise SMSSendError(f"솔라피 HTTP {e.code}: {detail}") from e  # 요청 거부 (인증 오류, 잔액 부족 등)
        except (TimeoutError, socket.timeout) as e:
            raise SMSUnknownResult("솔라피 응답 시간 초과") from e
        except urllib.error.URLError as e:
            if isinstance(e.reason, (TimeoutError, socket.timeout)):
                raise SMSUnknownResult("솔라피 응답 시간 초과") from e
            raise SMSSendError(f"솔라피 연결 실패: {e.reason}") from e

        try:
            data = json.loads(raw)
        except ValueError as e:
            raise SMSUnknownResult("솔라피 응답을 해석할 수 없음") from e

        failed = data.get("failedMessageList") or []
        if failed:
            first = failed[0]
            raise SMSSendError(f"솔라피 접수 실패 {first.get('statusCode')}: {first.get('statusMessage')}")
        group_id = (data.get("groupInfo") or {}).get("groupId") or ""
        logger.info("[SMS 솔라피 접수] to=%s group=%s", to, group_id)
        return group_id
