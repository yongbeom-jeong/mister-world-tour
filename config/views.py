from django.shortcuts import render

from apps.members.views import SHOW_HISTORY_POPUP


def home(request):
    """역할별 홈 메뉴 (설계 UI MainView). 고객 로그인 직후 한 번만 이력 팝업을 연다."""
    show_popup = request.session.pop(SHOW_HISTORY_POPUP, False)
    return render(request, "home.html", {"show_history_popup": show_popup})
