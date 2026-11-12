import json
from functools import lru_cache

from django.conf import settings
from django.db.models import Count
from django.shortcuts import render

from apps.members.views import SHOW_HISTORY_POPUP
from apps.souvenir import services as souvenir_services
from apps.travel import booking
from apps.travel.choices import DepartureStatus
from apps.travel.models import TravelApplication, TravelProduct

LOW_STOCK = 10  # 직원 대시보드에서 '재고 부족'으로 강조할 기준 (임시)


def home(request):
    """역할별 홈 (설계 UI MainView). 고객 로그인 직후 한 번만 이력 팝업을 연다."""
    ctx = {"show_history_popup": request.session.pop(SHOW_HISTORY_POPUP, False)}
    user = request.user
    if not user.is_authenticated or user.is_customer:
        ctx["featured"] = booking.bookable_products(None, None)[:6]
    if user.is_authenticated and user.is_customer:
        ctx["my_count"] = TravelApplication.objects.filter(member=user, cancelled_at__isnull=True).count()
    if user.is_authenticated and user.is_staff_member:
        souvenirs = souvenir_services.load_souvenirs()
        ctx["stats"] = {
            "products": TravelProduct.objects.count(),
            "applications": TravelApplication.objects.filter(cancelled_at__isnull=True).count(),
            "confirmed": TravelProduct.objects.filter(departure_status=DepartureStatus.CONFIRMED).count(),
            "low_stock": [s for s in souvenirs if s.stock_quantity < LOW_STOCK],
        }
        ctx["souvenirs"] = souvenirs
        ctx["recent_products"] = (TravelProduct.objects.annotate(n_apps=Count("applications"))
                                  .order_by("-created_at")[:5])
        ctx["low_stock_limit"] = LOW_STOCK
    return render(request, "home.html", ctx)


@lru_cache(maxsize=1)
def _credits() -> list[dict]:
    path = settings.BASE_DIR / "static" / "img" / "photos" / "credits.json"
    return json.loads(path.read_text(encoding="utf-8"))


def credits(request):
    """사진 출처 (CC BY·BY-SA 라이선스의 저작자 표시 의무)."""
    return render(request, "credits.html", {"credits": _credits()})
