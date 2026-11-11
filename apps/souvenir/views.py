import logging

from django.contrib import messages
from django.http import JsonResponse
from django.shortcuts import redirect, render
from django.urls import reverse
from django.views.decorators.http import require_GET, require_POST

from apps.members.decorators import staff_role_required
from apps.travel.choices import Theme

from . import services

logger = logging.getLogger(__name__)
PENDING_SESSION_KEY = "stock_receipt_pending"  # 요청 키별 입고 준비 문맥


def _souvenir_json(s) -> dict:
    return {"id": s.pk, "name": s.name, "theme": s.get_theme_display(), "stock_quantity": s.stock_quantity}


@staff_role_required
def stock_page(request):
    """UC8 기념품 입고 화면. 목록은 페이지가 열린 뒤 JSON 으로 불러온다."""
    return render(request, "souvenir/stock.html")


@staff_role_required
def register_page(request):
    """기념품 품목 등록: 이름과 연결할 테마. 등록 후 입고 화면에서 그 품목을 선택한 상태로 연다."""
    values, errors = {"name": "", "theme": ""}, {}
    if request.method == "POST":
        values = {"name": request.POST.get("name", "").strip(), "theme": request.POST.get("theme", "")}
        try:
            souvenir = services.register_souvenir(values["name"], values["theme"])
        except services.SouvenirRegistrationError as e:
            errors = e.errors
        else:
            messages.success(request, f"'{souvenir.name}'을(를) {souvenir.get_theme_display()}에 등록했습니다. "
                                      "입고 수량을 등록해 주세요.")
            return redirect(reverse("souvenir:stock") + f"?selected={souvenir.pk}")
    by_theme = {t: [] for t in Theme.values}
    for s in services.load_souvenirs():
        by_theme[s.theme].append(s)
    return render(request, "souvenir/register.html", {
        "values": values, "errors": errors,
        "themes": [(value, label, by_theme[value]) for value, label in Theme.choices],
    })


@staff_role_required
@require_GET
def souvenir_list(request):
    """LoadSouvenirs: 조회 실패는 빈 목록·재고 0과 구분해 오류로 응답."""
    try:
        souvenirs = services.load_souvenirs()
    except Exception:
        logger.exception("기념품 목록 조회 실패")
        return JsonResponse({"ok": False, "message": "기념품 목록을 불러오지 못했습니다."}, status=500)
    return JsonResponse({"ok": True, "souvenirs": [_souvenir_json(s) for s in souvenirs]})


@staff_role_required
@require_POST
def prepare_receipt(request):
    pending = request.session.get(PENDING_SESSION_KEY, {})
    try:
        result = services.prepare_stock_receipt(
            pending, request.user.pk, request.POST.get("souvenir_id"), request.POST.get("quantity")
        )
    except services.StockReceiptError as e:
        return JsonResponse({"ok": False, "status": e.status.value, "message": e.message}, status=400)
    request.session[PENDING_SESSION_KEY] = pending
    return JsonResponse({"ok": True, **result})


@staff_role_required
@require_POST
def record_receipt(request):
    pending = request.session.get(PENDING_SESSION_KEY, {})
    res = services.record_souvenir_stock_receipt(
        pending, request.user.pk, request.POST.get("souvenir_id"), request.POST.get("quantity"),
        request.POST.get("request_key", ""),
    )
    request.session[PENDING_SESSION_KEY] = pending
    body = {
        "ok": res.status == services.StockReceiptStatus.SUCCESS,
        "status": res.status.value,
        "message": res.message,
        "already_recorded": res.already_recorded,
        "stock_refresh_succeeded": res.stock_refresh_succeeded,
        "current_souvenir": _souvenir_json(res.current_souvenir) if res.current_souvenir else None,
    }
    if res.receipt:
        body["receipt"] = {"id": res.receipt.pk, "quantity": res.receipt.quantity,
                           "stock_after": res.receipt.stock_after}
    status = {"SUCCESS": 200, "INVALID_INPUT": 400, "NOT_FOUND": 404, "CONFLICT": 409}.get(res.status.value, 500)
    return JsonResponse(body, status=status)
