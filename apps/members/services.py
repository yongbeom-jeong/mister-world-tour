"""Member Management 서비스 (설계 04장 MemberManager).

다른 앱은 회원 정보를 이 모듈의 함수로만 다룬다.
"""

from django.db import IntegrityError, transaction

from .models import Member


class DuplicateAccountError(Exception):
    pass


def sign_up(*, member_type: str, username: str, password: str, name: str, address: str, contact: str) -> Member:
    """검증된 입력으로 회원을 저장한다. 비밀번호는 해시로 저장된다."""
    try:
        with transaction.atomic():
            return Member.objects.create_user(
                username=username, password=password, name=name, address=address, contact=contact, role=member_type,
            )
    except IntegrityError as e:  # 폼 검사 이후 동시에 같은 아이디가 가입된 경우
        raise DuplicateAccountError(username) from e


def find_member_joined_since(username: str, since) -> Member | None:
    """since 이후 이 아이디로 가입된 회원. 가입 저장 중 오류 뒤 가입 여부 확인용."""
    return Member.objects.filter(username__iexact=username, date_joined__gte=since).first()


def get_contact(member_id: int) -> tuple[str, str] | None:
    """문자 발송용 (성명, 연락처). 없는 회원이면 None."""
    row = Member.objects.filter(pk=member_id).values_list("name", "contact").first()
    return tuple(row) if row else None


def normalize_contact(value: str) -> str | None:
    """휴대전화 번호를 010-1234-5678 형식으로. 형식이 아니면 None."""
    from .forms import CONTACT_RE

    m = CONTACT_RE.match((value or "").strip())
    return "-".join(m.groups()) if m else None
