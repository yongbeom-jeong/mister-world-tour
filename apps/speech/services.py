"""Speech Recognition Integration (설계 08장 SpeechRecognitionManager · SpeechServiceClient · VoiceSelectionInterpreter).

- 지원 범위: 테마·투어 등급 조회 조건 지정, 현재 화면 목록에서 상품 선택. (옵션 변경·신청은 GUI)
- 해석 결과는 선택 값만 돌려준다. 상품 검증·등급 제한·가격은 Travel Management 가 GUI 와 같은 경로로 처리한다.
- 모호하거나 지원하지 않는 말은 적용할 값 없이 안내만 한다 (직전 선택 유지).
- 외부 음성인식 업체는 미정 → settings.SPEECH_CLIENT 로 교체.
  기본 BrowserTranscriptClient 는 브라우저(Web Speech API, ko-KR)가 인식한 문장을 받는다.
"""

import logging
import re
from dataclasses import dataclass, field
from enum import Enum

from django.conf import settings
from django.utils.module_loading import import_string

from apps.travel.choices import Theme, TourLevel

logger = logging.getLogger(__name__)


class VoiceSelectionStatus(str, Enum):
    SUCCESS = "SUCCESS"
    RECOGNITION_FAILED = "RECOGNITION_FAILED"
    AMBIGUOUS = "AMBIGUOUS"
    UNSUPPORTED = "UNSUPPORTED"


class VoiceSelectionKind(str, Enum):
    FILTER = "FILTER"    # 테마·등급 조회 조건
    PRODUCT = "PRODUCT"  # 상품 선택


@dataclass
class VoiceInput:
    transcript: str = ""     # 브라우저가 인식한 문장
    audio: bytes = b""       # 서버 측 인식 업체용 (미사용)
    audio_format: str = ""


@dataclass
class ProductCandidate:
    product_id: int
    name: str
    destination: str = ""


@dataclass
class VoiceSelectionContext:
    theme: str | None = None
    tour_level: str | None = None
    products: list[ProductCandidate] = field(default_factory=list)  # 화면에 보이는 순서


@dataclass
class SpeechRecognitionResult:
    ok: bool
    text: str = ""
    error: str = ""


@dataclass
class VoiceSelectionResult:
    status: VoiceSelectionStatus
    message: str
    kind: VoiceSelectionKind | None = None
    theme: str | None = None
    tour_level: str | None = None
    product_id: int | None = None
    recognized_text: str = ""

    def to_dict(self) -> dict:
        return {
            "status": self.status.value, "message": self.message,
            "kind": self.kind.value if self.kind else None,
            "theme": self.theme, "tour_level": self.tour_level, "product_id": self.product_id,
            "recognized_text": self.recognized_text,
        }


# ---------------------------------------------------------------- 외부 음성인식 클라이언트

class BrowserTranscriptClient:
    """브라우저 Web Speech API 가 이미 인식한 문장을 그대로 사용한다."""

    max_length = 200

    def recognize(self, voice_input: VoiceInput) -> SpeechRecognitionResult:
        text = (voice_input.transcript or "").strip()
        if not text:
            return SpeechRecognitionResult(False, error="음성을 인식하지 못했습니다.")
        if len(text) > self.max_length:
            return SpeechRecognitionResult(False, error="너무 긴 음성입니다. 짧게 다시 말해 주세요.")
        return SpeechRecognitionResult(True, text=text)


def get_client():
    return import_string(getattr(settings, "SPEECH_CLIENT", "apps.speech.services.BrowserTranscriptClient"))()


# ---------------------------------------------------------------- 해석

# 별칭은 임시 정책 (설계상 명령 문형·별칭은 연동 시험에서 구체화)
THEME_ALIASES = {
    Theme.HONEYMOON: ("허니문", "신혼", "신혼여행", "웨딩"),
    Theme.FILIAL: ("효도", "부모님", "효도여행", "효도힐링"),
    Theme.GOLF: ("골프",),
    Theme.TREKKING: ("트레킹", "트래킹", "등산", "아웃도어", "산행"),
}
LEVEL_ALIASES = {
    TourLevel.CLASSIC: ("클래식", "기본형", "실속"),
    TourLevel.GRAND: ("그랜드", "그랜", "중급"),
    TourLevel.PREMIUM: ("프리미엄", "프리미움", "고급형", "최고급"),
}
ORDINALS = {"첫": 1, "한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10}
# 상품 이름에서 고유 단어를 고를 때 제외할 흔한 단어
GENERIC_WORDS = {"투어", "여행", "챌린지", "힐링", "로맨틱", "트레킹", "골프", "허니문", "효도", "상품", "패키지"}
ORDINAL_RE = re.compile(r"(\d{1,2})\s*번|(첫|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\s*번\s*째")


def _norm(text: str) -> str:
    return re.sub(r"[\s.,!?·~\-]+", "", text.lower())


def _find(aliases: dict, text: str) -> set:
    return {key for key, words in aliases.items() if any(w in text for w in words)}


def _distinct_words(name: str, destination: str) -> set[str]:
    words = set(re.split(r"[\s·,()/]+", f"{name} {destination}"))
    out = set()
    for w in words:
        w = _norm(w)
        if len(w) >= 2 and w not in GENERIC_WORDS and not any(g in w for g in ("투어", "여행")):
            out.add(w)
    return out


def interpret_selection(recognized_text: str, context: VoiceSelectionContext) -> VoiceSelectionResult:
    text = _norm(recognized_text)

    # 1) 상품 선택: 순서("두 번째", "2번") 또는 상품 고유 단어(여행지·이름)
    m = ORDINAL_RE.search(recognized_text)
    if m and context.products:
        n = int(m.group(1)) if m.group(1) else ORDINALS[m.group(2)]
        if 1 <= n <= len(context.products):
            p = context.products[n - 1]
            return VoiceSelectionResult(VoiceSelectionStatus.SUCCESS, f"'{p.name}' 상품을 선택합니다.",
                                        VoiceSelectionKind.PRODUCT, product_id=p.product_id)
        return VoiceSelectionResult(VoiceSelectionStatus.UNSUPPORTED,
                                    f"{n}번째 상품이 목록에 없습니다. 지금 목록에는 {len(context.products)}개가 있습니다.")

    matches = [p for p in context.products
               if _norm(p.name) in text or any(w in text for w in _distinct_words(p.name, p.destination))]
    if len(matches) == 1:
        p = matches[0]
        return VoiceSelectionResult(VoiceSelectionStatus.SUCCESS, f"'{p.name}' 상품을 선택합니다.",
                                    VoiceSelectionKind.PRODUCT, product_id=p.product_id)
    if len(matches) > 1:
        names = ", ".join(p.name for p in matches[:3])
        return VoiceSelectionResult(VoiceSelectionStatus.AMBIGUOUS,
                                    f"여러 상품이 해당됩니다 ({names}). 상품 이름이나 순서(예: 두 번째)로 다시 말해 주세요.")

    # 2) 테마·등급 조회 조건: 말한 항목만 돌려주고 나머지는 화면 상태 유지
    themes, levels = _find(THEME_ALIASES, text), _find(LEVEL_ALIASES, text)
    if len(themes) > 1 or len(levels) > 1:
        return VoiceSelectionResult(VoiceSelectionStatus.AMBIGUOUS,
                                    "테마나 등급을 하나만 말해 주세요. 예: '골프 그랜드'")
    if themes or levels:
        theme = next(iter(themes), None)
        level = next(iter(levels), None)
        said = " ".join(x.label for x in (theme and Theme(theme), level and TourLevel(level)) if x)
        return VoiceSelectionResult(VoiceSelectionStatus.SUCCESS, f"{said} 상품을 찾습니다.",
                                    VoiceSelectionKind.FILTER, theme=theme, tour_level=level)

    return VoiceSelectionResult(VoiceSelectionStatus.UNSUPPORTED,
                                "테마·등급이나 상품을 말해 주세요. 예: '골프 그랜드', '몰디브', '두 번째 상품'")


# ---------------------------------------------------------------- 관리자 (SpeechRecognitionAPI)

def recognize_selection(voice_input: VoiceInput, context: VoiceSelectionContext, client=None) -> VoiceSelectionResult:
    client = client or get_client()
    try:
        recognized = client.recognize(voice_input)
    except Exception:
        logger.exception("음성인식 연동 실패")
        recognized = SpeechRecognitionResult(False, error="음성인식 서비스에 연결하지 못했습니다.")
    if not recognized.ok:
        logger.warning("음성 선택 실패 단계=인식 오류=%s", recognized.error)
        return VoiceSelectionResult(VoiceSelectionStatus.RECOGNITION_FAILED,
                                    recognized.error + " 다시 말하거나 화면에서 직접 선택해 주세요.")
    result = interpret_selection(recognized.text, context)
    result.recognized_text = recognized.text
    if result.status != VoiceSelectionStatus.SUCCESS:
        logger.info("음성 선택 %s text=%r", result.status.value, recognized.text)
    return result
