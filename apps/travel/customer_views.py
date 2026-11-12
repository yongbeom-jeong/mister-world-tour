"""고객 화면: 상품 선택·구성(UC4)과 여행 신청(UC5). 설계 UI 5장 TravelProductView, 6장 TravelApplicationView."""

import json
import logging

from django.contrib import messages
from django.db import DatabaseError
from django.http import JsonResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.views.decorators.http import require_POST

from apps.members.decorators import customer_required

from . import booking
from . import configuration as cfg
from .choices import (
    ALLOWED_LEVELS_FOR_RESTRICTED,
    ALLOWED_TRANSPORTS,
    INCLUDED_DRINKS,
    NO_DRINK_MEALS,
    DrinkOption,
    HotelLevel,
    MealOption,
    Theme,
    TourLevel,
    TransportOption,
)
from .forms import ProductFilterForm
from .models import TravelApplication
from .pricing import DRINK_PRICES, option_difference

DRAFT_KEY = "application_draft"      # 신청 화면으로 넘긴 상품·구성·입력 중인 여행자
REVIEWS_KEY = "application_reviews"  # 요청 키별 검토 내용
MAX_REVIEWS = 10
MAX_TRAVELER_ROWS = 10
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------- UC4 상품 목록

@customer_required
def product_browse(request):
    filters = ProductFilterForm(request.GET or None)
    theme = level = None
    notice = None
    if filters.is_valid():
        theme = filters.cleaned_data["theme"] or None
        level = filters.cleaned_data["tour_level"] or None
    if theme in (Theme.HONEYMOON, Theme.FILIAL) and level and level not in ALLOWED_LEVELS_FOR_RESTRICTED:
        notice = f"{Theme(theme).label}는 그랜드 또는 프리미엄 등급으로만 운영합니다."
        products = []
    else:
        products = booking.bookable_products(theme, level)
    return render(request, "travel/customer/products.html", {
        "filters": filters, "products": products, "notice": notice, "theme": theme, "level": level,
        "themes": Theme.choices, "levels": TourLevel.choices,
    })


# ---------------------------------------------------------------- UC4 상품 선택·구성

def _option_choices(product) -> dict:
    """화면에 보여줄 선택지와 기본 구성 대비 1인 추가금."""
    def diff(field, value) -> str:
        """선택지 옆에 붙일 1인 금액 차이 문구: '기본', '+150,000원', '−200,000원'."""
        if value == getattr(product, field):
            return "기본"
        d = option_difference(product, field, value)
        return "추가금 없음" if d == 0 else f"{'+' if d > 0 else '−'}{abs(d):,}원"

    return {
        "hotel_level": [(v, label, diff("hotel_level", v)) for v, label in HotelLevel.choices],
        "transport": [(v, TransportOption(v).label, diff("transport", v))
                      for v in TransportOption.values if v in ALLOWED_TRANSPORTS[Theme(product.theme)]],
        "meal": [(v, label, diff("meal", v)) for v, label in MealOption.choices],
        "drinks": [(v, label, DRINK_PRICES[v]) for v, label in DrinkOption.choices],
    }


@customer_required
def product_detail(request, product_id: int):
    try:
        product = booking.get_bookable_product(product_id)
    except booking.BookingError as e:
        messages.error(request, e.message)
        return redirect("travel:products")
    config = cfg.default_configuration(product)
    # 같은 상품을 다시 열면 직전에 고른 구성을 이어서 보여준다
    draft = request.session.get(DRAFT_KEY)
    if draft and draft.get("product_id") == product.pk:
        config = draft["config"]
    return render(request, "travel/customer/product_detail.html", {
        "product": product,
        "config": config,
        "config_json": json.dumps(config),
        "described": cfg.describe(config),
        "unit_price": booking.calculate_unit_price(product, config),
        "options": _option_choices(product),
        "option_fields": [("hotel_level", "호텔"), ("transport", "교통"), ("meal", "식사")],
        "no_drink_meals": json.dumps(sorted(NO_DRINK_MEALS)),
        "included_drinks": json.dumps({k: sorted(v) for k, v in INCLUDED_DRINKS.items()}),
        "remaining": booking.remaining_seats(product),
        "souvenirs": booking.theme_souvenir_names(product),
    })


@customer_required
@require_POST
def configure(request, product_id: int):
    """ConfigureTravelProduct: 현재 구성 + 변경 항목 → 새 구성·1인 가격 (JSON)."""
    try:
        product = booking.get_bookable_product(product_id)
        body = json.loads(request.body or b"{}")
        current, changes = body.get("current") or {}, body.get("changes") or {}
    except booking.BookingError as e:
        return JsonResponse({"ok": False, "message": e.message}, status=404)
    except (ValueError, AttributeError):
        return JsonResponse({"ok": False, "message": "잘못된 요청입니다."}, status=400)
    try:
        new, unit_price = booking.configure_travel_product(product, current, changes)
    except cfg.ConfigurationError as e:
        return JsonResponse({"ok": False, "message": str(e), "errors": e.errors}, status=400)
    return JsonResponse({"ok": True, "config": new, "described": cfg.describe(new), "unit_price": unit_price})


@customer_required
@require_POST
def start_application(request, product_id: int):
    """선택한 상품·구성을 신청 화면으로 넘긴다 (ShowApplication). 구성은 서버가 다시 검증한다."""
    try:
        product = booking.get_bookable_product(product_id)
        config = cfg.apply_changes(product, json.loads(request.POST.get("config") or "{}"), {})
    except booking.BookingError as e:
        messages.error(request, e.message)
        return redirect("travel:products")
    except (ValueError, cfg.ConfigurationError):
        messages.error(request, "여행 구성을 다시 확인해 주세요.")
        return redirect("travel:product_detail", product_id=product_id)
    request.session[DRAFT_KEY] = {"product_id": product.pk, "config": config, "travelers": []}
    return redirect("travel:apply")


# ---------------------------------------------------------------- UC5 신청

def _traveler_rows(request) -> list[dict]:
    names = request.POST.getlist("traveler_name")[:MAX_TRAVELER_ROWS]
    contacts = request.POST.getlist("traveler_contact")[:MAX_TRAVELER_ROWS]
    contacts += [""] * (len(names) - len(contacts))
    return [{"name": n, "contact": c} for n, c in zip(names, contacts)]


def _save_review(request, review: booking.Review) -> None:
    reviews = request.session.get(REVIEWS_KEY, {})
    reviews[review.request_key] = review.to_session()
    while len(reviews) > MAX_REVIEWS:
        reviews.pop(next(iter(reviews)))
    request.session[REVIEWS_KEY] = reviews


@customer_required
def apply(request):
    if request.method == "POST" and request.POST.get("action") == "submit":
        key = request.POST.get("request_key", "")
        if key not in request.session.get(REVIEWS_KEY, {}):
            done = booking.find_submitted_application(request.user.pk, key)
            if done:  # 이미 처리된 신청을 다시 제출 (뒤로 가기 후 재제출 등): 새로 저장하지 않고 기존 신청 안내
                messages.info(request, "이미 처리된 신청입니다. 신청 내용을 확인해 주세요.")
                return redirect("travel:application_detail", application_id=done.pk)
    draft = request.session.get(DRAFT_KEY)
    if not draft:
        messages.info(request, "먼저 신청할 여행 상품과 구성을 선택해 주세요.")
        return redirect("travel:products")
    try:
        product = booking.get_bookable_product(draft["product_id"])
    except booking.BookingError as e:
        request.session.pop(DRAFT_KEY, None)
        messages.error(request, e.message)
        return redirect("travel:products")

    ctx = {
        "product": product, "config": draft["config"], "described": cfg.describe(draft["config"]),
        "unit_price": booking.calculate_unit_price(product, draft["config"]),
        "remaining": booking.remaining_seats(product), "travelers": draft.get("travelers") or [],
        "errors": {}, "review": None,
    }
    action = request.POST.get("action") if request.method == "POST" else None

    if action == "review":
        rows = _traveler_rows(request)
        draft["travelers"] = rows
        request.session[DRAFT_KEY] = draft
        ctx["travelers"] = rows
        try:
            review = booking.review_application(request.user, product.pk, draft["config"], rows)
        except booking.BookingError as e:
            messages.error(request, e.message)
            ctx["errors"] = e.errors
        else:
            _save_review(request, review)
            ctx["review"] = review

    elif action == "submit":
        key = request.POST.get("request_key", "")
        data = request.session.get(REVIEWS_KEY, {}).get(key)
        if not data:
            messages.error(request, "검토 내용이 만료되었습니다. 금액을 다시 확인해 주세요.")
        else:
            review = booking.Review.from_session(key, data)
            try:
                result = booking.submit_travel_application(request.user, review)
            except booking.BookingError as e:
                messages.error(request, e.message)
                ctx["errors"] = e.errors
            except DatabaseError:
                logger.exception("신청 등록 중 DB 오류 member=%s key=%s", request.user.pk, key)
                done = _find_after_db_error(request.user.pk, key)
                if done:  # 저장은 끝났고 그 뒤에 오류: 검토 내용을 정리하고 저장된 신청으로 안내
                    _forget_review(request, key)
                    messages.warning(request, "처리 중 오류가 있었지만 신청은 저장되었습니다. 신청 내용을 확인해 주세요.")
                    return redirect("travel:application_detail", application_id=done.pk)
                if done is False:
                    messages.error(request, "일시적인 오류로 신청이 저장되지 않았습니다. 잠시 후 '신청하기'를 다시 눌러 주세요.")
                else:
                    messages.error(request, "일시적인 오류로 신청 반영 여부를 확인하지 못했습니다. "
                                            "잠시 후 '내 신청'에서 확인하고, 없으면 다시 신청해 주세요.")
                ctx["review"] = review  # 같은 요청 키로 다시 제출 (저장됐다면 중복 없이 기존 신청 안내)
            else:
                _forget_review(request, key)
                _flash_result(request, result)
                return redirect("travel:application_detail", application_id=result.application.pk)

    return render(request, "travel/customer/apply.html", ctx)


def _forget_review(request, key: str) -> None:
    """신청이 저장된 뒤 선택 상품·구성과 그 검토 내용을 세션에서 지운다."""
    request.session.pop(DRAFT_KEY, None)
    reviews = request.session.get(REVIEWS_KEY, {})
    reviews.pop(key, None)
    request.session[REVIEWS_KEY] = reviews


def _find_after_db_error(member_id: int, key: str):
    """DB 오류 뒤 반영 여부 확인. 저장된 신청 / False(저장 안 됨) / None(확인 불가)."""
    try:
        return booking.find_submitted_application(member_id, key) or False
    except DatabaseError:
        logger.exception("신청 반영 여부 확인 실패 member=%s key=%s", member_id, key)
        return None


def _flash_result(request, result: booking.ApplicationResult) -> None:
    if result.duplicate:
        messages.info(request, "이미 처리된 신청입니다. 같은 신청을 다시 등록하지 않았습니다.")
        return
    messages.success(request, "여행 신청이 완료되었습니다.")
    if result.application.product.is_honeymoon:
        messages.success(request, "허니문 2인 신청으로 출발이 확정되었습니다.")
    elif result.newly_confirmed:
        messages.success(request, "이번 신청으로 출발 인원(3명)이 채워져 출발이 확정되었습니다!")
    elif result.departure_status == "CONFIRMED":
        messages.success(request, "이미 출발이 확정된 상품입니다.")
    else:
        messages.info(request, "아직 출발 인원(3명)이 모이지 않아 출발 미확정 상태입니다. 확정되면 문자로 알려 드립니다.")
    if result.sms is not None:
        level = messages.WARNING if (result.sms.failed or result.sms.unknown) else messages.INFO
        text = result.sms.summary()
        if result.sms.failed or result.sms.unknown:
            text += " (신청과 출발 확정은 정상 저장되었습니다.)"
        messages.add_message(request, level, text)


@customer_required
def application_detail(request, application_id: int):
    app = get_object_or_404(
        TravelApplication.objects.select_related("product"), pk=application_id, member=request.user
    )
    config = {"hotel_level": app.hotel_level, "transport": app.transport, "meal": app.meal,
              "drinks": cfg.normalize_drinks(app.extra_drinks)}
    return render(request, "travel/customer/application_detail.html", {
        "app": app, "travelers": app.travelers.all(), "described": cfg.describe(config),
        "status": app.current_departure_status(),
    })


@customer_required
def my_applications(request):
    return render(request, "travel/customer/my_applications.html",
                  {"applications": booking.my_applications(request.user.pk)})
