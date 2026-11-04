import logging

from django.contrib import messages
from django.http import JsonResponse
from django.shortcuts import redirect, render
from django.views.decorators.http import require_GET, require_POST

from apps.members.decorators import customer_required, staff_role_required

from . import images, services
from .forms import ProductFilterForm, ProductForm

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------- 고객: 이전 여행 이력 (UC3)

@customer_required
def history_partial(request):
    """홈 화면 팝업이 fetch 로 불러가는 HTML 조각."""
    try:
        histories = services.view_previous_travel_history(request.user.pk)
    except Exception:  # 조회 실패는 '이력 없음'과 구분해 실패로 응답
        logger.exception("여행 이력 조회 실패 member=%s", request.user.pk)
        return render(request, "travel/_history_error.html", status=500)
    return render(request, "travel/_history_list.html", {"histories": histories})


# ---------------------------------------------------------------- 직원: 상품 등록 (UC6)

@staff_role_required
def product_register(request):
    """등록 화면. 관리 화면과 분리되어 있고 항상 빈 입력으로 시작한다."""
    form = ProductForm(request.POST or None, request.FILES or None)
    if request.method == "POST" and form.is_valid():
        try:
            product = services.register_tour_product(form.input_data(), image=form.image_change())
        except images.InvalidImage as e:
            form.add_error("image", str(e))
        else:
            messages.success(request, f"'{product.name}' 상품을 등록했습니다.")
            return redirect("travel:product_manage_detail", product_id=product.pk)
    return render(request, "travel/staff/product_register.html", {"form": form})


@staff_role_required
@require_GET
def default_configuration(request):
    """테마·등급의 기본 구성 (등록·수정 화면 확인용 JSON)."""
    try:
        config = services.load_default_configuration(request.GET.get("theme", ""), request.GET.get("tour_level", ""))
    except ValueError as e:
        return JsonResponse({"ok": False, "message": str(e)}, status=400)
    return JsonResponse({"ok": True, "configuration": config})


# ---------------------------------------------------------------- 직원: 상품 관리 (UC7)

def _manage_context(request, filters: ProductFilterForm) -> dict:
    theme = level = None
    if filters.is_valid():
        theme, level = filters.cleaned_data["theme"] or None, filters.cleaned_data["tour_level"] or None
    return {"products": services.load_products(theme, level), "filters": filters}


@staff_role_required
def product_manage(request):
    """관리 화면 첫 진입: 목록만 보이고 선택 대상은 비어 있다."""
    ctx = _manage_context(request, ProductFilterForm(request.GET or None))
    return render(request, "travel/staff/product_manage.html", ctx)


@staff_role_required
def product_manage_detail(request, product_id: int):
    """목록에서 상품을 선택한 상태. 선택만으로는 아무것도 바뀌지 않고, 수정 버튼(POST)으로만 수정한다."""
    try:
        selected = services.select_product(product_id)
    except services.ProductNotFound:
        messages.error(request, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
        return redirect("travel:product_manage")

    # 폼은 별도로 조회한 객체를 편집한다 (입력 중인 값이 표시용 원본을 덮어쓰지 않도록)
    form = ProductForm(request.POST or None, request.FILES or None, instance=services.select_product(product_id))
    if request.method == "POST" and form.is_valid():
        try:
            product = services.modify_tour_product(product_id, form.input_data(), image=form.image_change())
        except images.InvalidImage as e:
            form.add_error("image", str(e))
        except services.ProductHasApplications:
            messages.error(request, "신청이 있는 상품은 수정할 수 없습니다. 기존 정보를 유지했습니다.")
        except services.ProductNotFound:
            messages.error(request, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
            return redirect("travel:product_manage")
        else:
            messages.success(request, f"'{product.name}' 상품을 수정했습니다.")
            return redirect("travel:product_manage_detail", product_id=product_id)

    ctx = _manage_context(request, ProductFilterForm(request.GET or None))
    ctx.update(
        selected=selected,
        form=form,
        has_applications=services.has_applications(product_id),
        default_config=services.load_default_configuration(selected.theme, selected.tour_level),
    )
    return render(request, "travel/staff/product_manage.html", ctx)


@staff_role_required
@require_POST
def product_delete(request, product_id: int):
    try:
        name = services.delete_tour_product(product_id)
    except services.ProductHasApplications:
        messages.error(request, "신청이 있는 상품은 삭제할 수 없습니다.")
        return redirect("travel:product_manage_detail", product_id=product_id)
    except services.ProductNotFound:
        messages.error(request, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")
    else:
        messages.success(request, f"'{name}' 상품을 삭제했습니다.")
    return redirect("travel:product_manage")

