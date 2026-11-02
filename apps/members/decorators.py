"""역할별 접근 제한. 화면 메뉴 표시와 별개로 서버에서 항상 역할을 다시 확인한다."""

from functools import wraps

from django.contrib.auth.decorators import login_required
from django.core.exceptions import PermissionDenied

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
