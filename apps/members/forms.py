import re

from django import forms
from django.conf import settings
from django.contrib.auth import password_validation

from config.forms import BootstrapFormMixin

from .models import Member

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{4,20}$")
CONTACT_RE = re.compile(r"^(01[016789])-?(\d{3,4})-?(\d{4})$")


class SignUpForm(BootstrapFormMixin, forms.Form):
    """UC1 회원가입 입력 6개(유형·아이디·비밀번호·성명·주소·연락처) + 확인용 입력."""

    member_type = forms.ChoiceField(
        label="회원 유형", choices=Member.Role.choices, initial=Member.Role.CUSTOMER, widget=forms.RadioSelect
    )
    username = forms.CharField(label="아이디", max_length=20, help_text="영문·숫자·밑줄 4~20자")
    password1 = forms.CharField(
        label="비밀번호", strip=False, widget=forms.PasswordInput,
        help_text="8자 이상. 숫자로만 된 비밀번호, 흔한 비밀번호(password123 등), 아이디·성명과 비슷한 비밀번호는 쓸 수 없습니다.",
    )
    password2 = forms.CharField(label="비밀번호 확인", strip=False, widget=forms.PasswordInput)
    name = forms.CharField(label="성명", max_length=50)
    address = forms.CharField(label="주소", max_length=200)
    contact = forms.CharField(label="연락처", max_length=20, help_text="예: 010-1234-5678 (출발 확정 문자를 받을 번호)")
    staff_code = forms.CharField(
        label="직원 인증 코드", required=False, widget=forms.PasswordInput,
        help_text="직원 유형으로 가입할 때만 입력",
    )

    def clean_username(self):
        username = self.cleaned_data["username"]
        if not USERNAME_RE.match(username):
            raise forms.ValidationError("아이디는 영문·숫자·밑줄(_)로 4~20자여야 합니다.")
        if Member.objects.filter(username__iexact=username).exists():
            raise forms.ValidationError("이미 사용 중인 아이디입니다.")
        return username

    def clean_contact(self):
        m = CONTACT_RE.match(self.cleaned_data["contact"].strip())
        if not m:
            raise forms.ValidationError("휴대전화 번호 형식이 아닙니다. 예: 010-1234-5678")
        return "-".join(m.groups())  # 하이픈 형식으로 통일

    def clean(self):
        data = super().clean()
        p1, p2 = data.get("password1"), data.get("password2")
        if p1 and p2 and p1 != p2:
            self.add_error("password2", "비밀번호가 일치하지 않습니다.")
        elif p1:
            temp = Member(username=data.get("username", ""), name=data.get("name", ""))
            try:
                password_validation.validate_password(p1, temp)
            except forms.ValidationError as e:
                self.add_error("password1", e)
        if data.get("member_type") == Member.Role.STAFF and data.get("staff_code") != settings.STAFF_SIGNUP_CODE:
            self.add_error("staff_code", "직원 인증 코드가 올바르지 않습니다.")
        return data
