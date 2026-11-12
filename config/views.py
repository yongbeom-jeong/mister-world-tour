import json
from functools import lru_cache

from django.conf import settings
from django.shortcuts import render

from apps.souvenir import services as souvenir_services
from apps.travel import booking
from apps.travel import services as travel_services

LOW_STOCK = 10  # 직원 대시보드에서 '재고 부족'으로 강조할 기준 (임시)


def home(request):
    """역할별 홈 (설계 UI MainView). 고객 로그인 직후 한 번만 이력 팝업을 연다."""
    ctx = {}  # 로그인 직후 이력 팝업은 base.html + members.context_processors.history_popup 이 처리
    user = request.user
    if not user.is_authenticated or user.is_customer:
        ctx["featured"] = booking.bookable_products(None, None)[:6]
    if user.is_authenticated and user.is_customer:
        ctx["my_count"] = booking.active_application_count(user.pk)
    if user.is_authenticated and user.is_staff_member:
        souvenirs = souvenir_services.load_souvenirs()
        ctx["stats"] = {**travel_services.staff_dashboard_counts(),
                        "low_stock": [s for s in souvenirs if s.stock_quantity < LOW_STOCK]}
        ctx["souvenirs"] = souvenirs
        ctx["recent_products"] = travel_services.recent_products(5)
        ctx["low_stock_limit"] = LOW_STOCK
    return render(request, "home.html", ctx)


@lru_cache(maxsize=1)
def _credits() -> list[dict]:
    path = settings.BASE_DIR / "static" / "img" / "photos" / "credits.json"
    return json.loads(path.read_text(encoding="utf-8"))


def credits(request):
    """사진 출처 (CC BY·BY-SA 라이선스의 저작자 표시 의무)."""
    return render(request, "credits.html", {"credits": _credits()})
