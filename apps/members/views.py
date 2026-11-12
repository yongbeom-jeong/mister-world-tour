import logging

from django.contrib import messages
from django.contrib.auth import views as auth_views
from django.db import DatabaseError
from django.shortcuts import redirect, render
from django.utils import timezone

from . import services
from .forms import SignUpForm

SHOW_HISTORY_POPUP = "show_history_popup"
logger = logging.getLogger(__name__)


def sign_up(request):
    """UC1 회원가입."""
    if request.user.is_authenticated:
        return redirect("home")
    form = SignUpForm(request.POST or None)
    if request.method == "POST" and form.is_valid():
        d = form.cleaned_data
        started = timezone.now()
        try:
            services.sign_up(
                member_type=d["member_type"], username=d["username"], password=d["password1"],
                name=d["name"], address=d["address"], contact=d["contact"],
            )
        except services.DuplicateAccountError:
            form.add_error("username", "이미 사용 중인 아이디입니다.")
        except DatabaseError:  # UC1 5a: 저장 결과를 확인해 안내 (가입 완료로 잘못 안내하지 않음)
            logger.exception("회원가입 저장 중 DB 오류 username=%s", d["username"])
            joined = _find_after_error(d["username"], started)
            if joined:
                messages.warning(request, "처리 중 오류가 있었지만 가입은 완료되었습니다. 로그인해 주세요.")
                return redirect("members:login")
            if joined is False:
                messages.error(request, "일시적인 오류로 가입되지 않았습니다. 잠시 후 다시 가입해 주세요.")
            else:
                messages.error(request, "일시적인 오류로 가입 여부를 확인하지 못했습니다. "
                                        "잠시 후 같은 아이디로 로그인해 보고, 안 되면 다시 가입해 주세요.")
        else:
            messages.success(request, "회원가입이 완료되었습니다. 로그인해 주세요.")
            return redirect("members:login")
    return render(request, "members/signup.html", {"form": form})


def _find_after_error(username: str, since):
    """가입 저장 오류 뒤 가입 여부. 회원 / False(가입 안 됨) / None(확인 불가)."""
    try:
        return services.find_member_joined_since(username, since) or False
    except DatabaseError:
        logger.exception("가입 여부 확인 실패 username=%s", username)
        return None


class LoginView(auth_views.LoginView):
    """UC2 로그인. 고객이면 홈에서 이전 여행 이력 팝업을 띄우도록 표시한다."""

    template_name = "members/login.html"
    redirect_authenticated_user = True

    def form_valid(self, form):
        response = super().form_valid(form)
        if self.request.user.is_customer:
            self.request.session[SHOW_HISTORY_POPUP] = True
        return response
