from datetime import timedelta

from django import forms
from django.utils import timezone

from config.forms import BootstrapFormMixin

from . import configuration as cfg
from .choices import Theme, TourLevel
from .models import TravelProduct
from .services import BASE_CONFIG_FIELDS, PRODUCT_INPUT_FIELDS

# 임시 정책: 입력 범위 (설계 미정)
MIN_PRICE, MAX_PRICE = 10_000, 50_000_000
MIN_DAYS, MAX_DAYS = 1, 30


class ProductForm(BootstrapFormMixin, forms.ModelForm):
    """UC6·UC7 상품 입력. 기본 구성은 테마·등급 기본값을 보여 주고 직원이 확인한 뒤 바꿀 수 있다 (UC6 (3))."""

    class Meta:
        model = TravelProduct
        fields = (*PRODUCT_INPUT_FIELDS, *BASE_CONFIG_FIELDS, "image")
        widgets = {
            "image": forms.ClearableFileInput(attrs={"accept": "image/jpeg,image/png,image/webp"}),
            "departure_date": forms.DateInput(attrs={"type": "date"}, format="%Y-%m-%d"),
            "description": forms.Textarea(attrs={"rows": 3}),
        }
        help_texts = {
            "base_price": f"1인 기준, {MIN_PRICE:,}~{MAX_PRICE:,}원",
            "travel_days": f"{MIN_DAYS}~{MAX_DAYS}일",
            "departure_date": "내일 이후 날짜",
            "tour_level": "허니문·효도는 그랜드 또는 프리미엄만 가능",
            "image": "JPG·PNG, 5MB 이하. 올리지 않으면 테마 기본 사진을 씁니다.",
        }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.fields["destination"].required = True  # 고객 화면 표시에 쓰므로 필수 (임시 정책)
        # 달력에서 오늘 이전 날짜를 고르지 못하게 (최종 검사는 clean_departure_date)
        self.fields["departure_date"].widget.attrs["min"] = (timezone.localdate() + timedelta(days=1)).isoformat()
        # 빈 선택지 문구 ('---------' 대신)
        self.fields["theme"].choices = [("", "테마를 고르세요"), *Theme.choices]
        self.fields["tour_level"].choices = [("", "등급을 고르세요"), *TourLevel.choices]
        for f in BASE_CONFIG_FIELDS:  # 비우면 테마·등급 기본값
            self.fields[f].required = False
        self.fields["hotel_level"].label = "기본 호텔"
        self.fields["meal"].label = "기본 식사"
        self.fields["transport"].label = "기본 교통"

    def clean_base_price(self):
        v = self.cleaned_data["base_price"]
        if not MIN_PRICE <= v <= MAX_PRICE:
            raise forms.ValidationError(f"1인 기본 가격은 {MIN_PRICE:,}원 이상 {MAX_PRICE:,}원 이하로 입력해 주세요.")
        return v

    def clean_travel_days(self):
        v = self.cleaned_data["travel_days"]
        if not MIN_DAYS <= v <= MAX_DAYS:
            raise forms.ValidationError(f"여행 기간은 {MIN_DAYS}~{MAX_DAYS}일로 입력해 주세요.")
        return v

    def clean_departure_date(self):
        v = self.cleaned_data["departure_date"]
        if v <= timezone.localdate():
            raise forms.ValidationError("출발일은 내일 이후로 지정해 주세요.")
        return v

    @property
    def base_config_fields(self):
        """화면의 기본 구성 선택 상자 (호텔·식사·교통 순)."""
        return [self[f] for f in BASE_CONFIG_FIELDS]

    def clean(self):
        data = super().clean()
        theme, level = data.get("theme"), data.get("tour_level")
        if theme and level:
            for field, message in cfg.base_configuration_errors(theme, level, data).items():
                self.add_error(field, message)
            defaults = cfg.base_option_choices(theme, level)
            for f in BASE_CONFIG_FIELDS:  # 비운 항목은 테마·등급 기본값
                if f in data and not data[f]:
                    data[f] = defaults[f][0]
        return data

    def input_data(self) -> dict:
        return {f: self.cleaned_data.get(f) for f in (*PRODUCT_INPUT_FIELDS, *BASE_CONFIG_FIELDS)}

    def image_change(self):
        """사진 변경 요청: None=그대로, False=삭제, 업로드 파일=교체."""
        if "image" not in self.changed_data:
            return None
        value = self.cleaned_data.get("image")
        return False if value is False else value


class ProductFilterForm(BootstrapFormMixin, forms.Form):
    """목록 조건. 같은 화면의 상품 입력 폼과 id 가 겹치지 않도록 id 앞에 filter_ 를 붙인다 (파라미터 이름은 그대로)."""

    def __init__(self, *args, **kwargs):
        kwargs.setdefault("auto_id", "filter_%s")
        super().__init__(*args, **kwargs)

    theme = forms.ChoiceField(label="테마", required=False, choices=[("", "전체 테마"), *Theme.choices])
    tour_level = forms.ChoiceField(label="등급", required=False, choices=[("", "전체 등급"), *TourLevel.choices])
