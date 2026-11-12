from .views import SHOW_HISTORY_POPUP


def history_popup(request):
    """고객 로그인 직후 처음 그리는 화면에서 이력 팝업을 한 번 열도록 표시를 꺼낸다 (UC2 (4))."""
    user = getattr(request, "user", None)
    if not (user and user.is_authenticated and user.is_customer):
        return {"show_history_popup": False}
    return {"show_history_popup": request.session.pop(SHOW_HISTORY_POPUP, False)}
