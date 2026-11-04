from django.contrib import messages
from django.contrib.auth import views as auth_views
from django.shortcuts import redirect, render

from . import services
from .forms import SignUpForm

SHOW_HISTORY_POPUP = "show_history_popup"


def sign_up(request):
    """UC1 회원가입."""
    if request.user.is_authenticated:
        return redirect("home")
    form = SignUpForm(request.POST or None)
    if request.method == "POST" and form.is_valid():
        d = form.cleaned_data
        try:
            services.sign_up(
                member_type=d["member_type"], username=d["username"], password=d["password1"],
                name=d["name"], address=d["address"], contact=d["contact"],
            )
        except services.DuplicateAccountError:
            form.add_error("username", "이미 사용 중인 아이디입니다.")
        else:
            messages.success(request, "회원가입이 완료되었습니다. 로그인해 주세요.")
            return redirect("members:login")
    return render(request, "members/signup.html", {"form": form})


class LoginView(auth_views.LoginView):
    """UC2 로그인. 고객이면 홈에서 이전 여행 이력 팝업을 띄우도록 표시한다."""

    template_name = "members/login.html"
    redirect_authenticated_user = True

    def form_valid(self, form):
        response = super().form_valid(form)
        if self.request.user.is_customer:
            self.request.session[SHOW_HISTORY_POPUP] = True
        return response
