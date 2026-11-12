"""직원: 문자 발송 기록 조회와 재발송."""

from django.contrib import messages
from django.core.paginator import Paginator
from django.shortcuts import redirect, render
from django.urls import reverse
from django.views.decorators.http import require_POST

from apps.members.decorators import staff_role_required

from . import services

PAGE_SIZE = 30


@staff_role_required
def log_list(request):
    problems_only = request.GET.get("filter") == "problems"
    page = Paginator(services.load_logs(problems_only), PAGE_SIZE).get_page(request.GET.get("page"))
    return render(request, "sms/log_list.html", {
        "page": page, "problems_only": problems_only,
        "problem_count": services.load_logs(problems_only=True).count(),
    })


@staff_role_required
@require_POST
def resend(request, log_id: int):
    try:
        log = services.resend_logged_sms(log_id, request.user.pk,
                                         confirm_duplicate=request.POST.get("confirm_duplicate") == "1")
    except services.SMSResendError as e:
        messages.error(request, str(e))
    else:
        level = messages.SUCCESS if log.status == log.Status.SUCCESS else messages.WARNING
        text = f"{log.recipient_name}님에게 다시 보냈습니다: {log.get_status_display()}"
        if log.last_error and log.status != log.Status.SUCCESS:
            text += f" ({log.last_error})"
        messages.add_message(request, level, text)
    back = request.POST.get("next", "")
    return redirect(back if back.startswith("/sms/") else reverse("sms:logs"))
