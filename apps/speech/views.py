import json

from django.http import HttpResponse, JsonResponse
from django.views.decorators.http import require_POST

from apps.members.decorators import customer_required
from apps.travel import booking
from apps.travel.choices import Theme, TourLevel

from . import services


@customer_required
@require_POST
def recognize(request):
    """SelectByVoice: 인식 문장 + 현재 화면 조건 → 선택 값 (JSON). 화면 상태는 브라우저가 바꾼다."""
    try:
        body = json.loads(request.body or b"{}")
    except ValueError:
        return JsonResponse({"status": "RECOGNITION_FAILED", "message": "잘못된 요청입니다."}, status=400)
    theme = body.get("theme") if body.get("theme") in Theme.values else None
    level = body.get("tour_level") if body.get("tour_level") in TourLevel.values else None
    # 문맥의 상품 목록은 서버가 같은 조건으로 다시 조회한다 (화면과 같은 순서)
    products = [services.ProductCandidate(p.pk, p.name, p.destination)
                for p in booking.bookable_products(theme, level)]
    context = services.VoiceSelectionContext(theme, level, products)
    result = services.recognize_selection(services.VoiceInput(transcript=str(body.get("transcript", ""))), context)
    return JsonResponse(result.to_dict())


@customer_required
@require_POST
def browser_failure(request):
    """브라우저 음성 인식 실패 기록 (UC4 4a·7a). 화면 안내는 브라우저가 이미 했으므로 본문 없이 응답."""
    try:
        body = json.loads(request.body or b"{}")
    except ValueError:
        body = {}
    services.record_browser_failure(str(body.get("error", ""))[:40], request.user.pk)
    return HttpResponse(status=204)
