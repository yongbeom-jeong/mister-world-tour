import logging

from django.contrib import messages
from django.core.paginator import Paginator
from django.http import JsonResponse
from django.shortcuts import redirect, render
from django.urls import reverse
from django.views.decorators.http import require_GET, require_POST

from apps.members.decorators import staff_role_required, staff_role_required_json
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


@staff_role_required_json
@require_GET
def souvenir_list(request):
    """LoadSouvenirs: 조회 실패는 빈 목록·재고 0과 구분해 오류로 응답."""
    try:
        souvenirs = services.load_souvenirs()
    except Exception:
        logger.exception("기념품 목록 조회 실패")
        return JsonResponse({"ok": False, "message": "기념품 목록을 불러오지 못했습니다."}, status=500)
    return JsonResponse({"ok": True, "souvenirs": [_souvenir_json(s) for s in souvenirs]})


@staff_role_required_json
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


@staff_role_required_json
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
    status = {"SUCCESS": 200, "INVALID_INPUT": 400, "PERMISSION_DENIED": 403, "NOT_FOUND": 404,
              "CONFLICT": 409}.get(res.status.value, 500)
    return JsonResponse(body, status=status)


# ---------------------------------------------------------------- 품목 관리 (수정·삭제)·차감 내역

def _by_theme(souvenirs) -> list:
    by_theme = {t: [] for t in Theme.values}
    for s in souvenirs:
        by_theme[s.theme].append(s)
    return [(value, label, by_theme[value]) for value, label in Theme.choices]


def _warn_if_theme_empty(request, theme: str) -> None:
    if not services.theme_has_souvenir(theme):
        messages.warning(request, f"{Theme(theme).label}에 연결된 기념품이 없어 이 테마 상품은 신청할 수 없습니다. "
                                  "기념품을 등록하고 입고해 주세요.")


@staff_role_required
def manage_page(request):
    return render(request, "souvenir/manage.html", {"themes": _by_theme(services.load_souvenirs())})


@staff_role_required
def edit_page(request, souvenir_id: int):
    try:
        souvenir = services.get_souvenir(souvenir_id)
    except services.SouvenirNotFound:
        messages.error(request, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
        return redirect("souvenir:manage")
    values, errors = {"name": souvenir.name, "theme": souvenir.theme}, {}
    if request.method == "POST":
        values = {"name": request.POST.get("name", "").strip(), "theme": request.POST.get("theme", "")}
        old_theme = souvenir.theme
        try:
            updated = services.update_souvenir(souvenir_id, values["name"], values["theme"])
        except services.SouvenirRegistrationError as e:
            errors = e.errors
        except services.SouvenirNotFound:
            messages.error(request, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
            return redirect("souvenir:manage")
        else:
            messages.success(request, f"'{updated.name}' 정보를 수정했습니다.")
            if old_theme != updated.theme:
                _warn_if_theme_empty(request, old_theme)
            return redirect("souvenir:manage")
    return render(request, "souvenir/edit.html", {
        "souvenir": souvenir, "values": values, "errors": errors, "themes": Theme.choices,
        "issue_count": souvenir.issues.count(), "receipt_count": souvenir.receipts.count(),
    })


@staff_role_required
@require_POST
def delete_souvenir(request, souvenir_id: int):
    try:
        souvenir = services.get_souvenir(souvenir_id)
        removed = services.delete_souvenir(souvenir_id)
    except services.SouvenirNotFound:
        messages.error(request, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
        return redirect("souvenir:manage")
    if removed:
        messages.success(request, f"'{souvenir.name}'을(를) 삭제했습니다.")
    else:
        messages.success(request, f"'{souvenir.name}'을(를) 삭제했습니다. 입고·차감 내역은 기록으로 남겨 둡니다.")
    _warn_if_theme_empty(request, souvenir.theme)
    return redirect("souvenir:manage")


@staff_role_required
def issue_list(request):
    selected = request.GET.get("souvenir")
    selected = int(selected) if selected and selected.isdigit() else None
    page = Paginator(services.load_issues(selected), 30).get_page(request.GET.get("page"))
    return render(request, "souvenir/issues.html", {
        "page": page, "selected": selected, "souvenirs": services.load_all_souvenirs(),
    })
