"""역할별 접근 제한. 화면 메뉴 표시와 별개로 서버에서 항상 역할을 다시 확인한다."""

from functools import wraps

from django.contrib.auth.decorators import login_required
from django.core.exceptions import PermissionDenied
from django.http import JsonResponse

from .models import Member


def role_required(role: str):
    def decorator(view):
        @login_required
        @wraps(view)
        def wrapper(request, *args, **kwargs):
            if request.user.role != role:
                raise PermissionDenied("이 기능을 사용할 권한이 없습니다.")
            return view(request, *args, **kwargs)

        return wrapper

    return decorator


customer_required = role_required(Member.Role.CUSTOMER)
staff_role_required = role_required(Member.Role.STAFF)


def staff_role_required_json(view):
    """JSON 으로 응답하는 직원 기능용. 로그인 만료·권한 없음을 로그인 화면 이동 대신 JSON 403 으로 알린다
    (화면 스크립트가 '권한 거부'를 '반영 불명'과 구분할 수 있게, 설계 06장 StockReceiptStatus.PermissionDenied)."""
    @wraps(view)
    def wrapper(request, *args, **kwargs):
        if not request.user.is_authenticated or request.user.role != Member.Role.STAFF:
            return JsonResponse({"ok": False, "status": "PERMISSION_DENIED",
                                 "message": "로그인이 만료되었거나 권한이 없습니다. 다시 로그인해 주세요."}, status=403)
        return view(request, *args, **kwargs)

    return wrapper
