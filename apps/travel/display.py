"""화면 표시용 정보: 테마 소개 문구와 사진 경로. 업무 규칙과는 무관하다."""

from django.templatetags.static import static

from .choices import Theme

# 테마별 소개 (과제 안내의 테마 설명을 바탕으로 한 화면 문구)
THEME_INFO = {
    Theme.HONEYMOON: {
        "en": "Honeymoon Romance", "icon": "bi-heart",
        "tagline": "둘만을 위한 로맨틱 스페셜 룸과 2인 전용 고급차량",
        "photo": "theme_honeymoon",
    },
    Theme.FILIAL: {
        "en": "Parents Healing", "icon": "bi-flower1",
        "tagline": "고품격 안마·지압 서비스와 건강 인삼 선물",
        "photo": "theme_filial",
    },
    Theme.GOLF: {
        "en": "Golf Challenge", "icon": "bi-flag",
        "tagline": "명문 골프 리조트 라운드와 골프공 기념품",
        "photo": "theme_golf",
    },
    Theme.TREKKING: {
        "en": "Outdoor Trekking", "icon": "bi-tree",
        "tagline": "산과 길이 주는 모험, 아웃도어 스카프와 함께",
        "photo": "theme_trekking",
    },
}


def photo_url(name: str, small: bool = False) -> str:
    return static(f"img/photos/{'sm/' if small else ''}{name}.jpg")


def themes() -> list[dict]:
    return [
        {"code": t.value, "label": t.label, **info,
         "photo_url": photo_url(info["photo"]), "photo_sm": photo_url(info["photo"], small=True)}
        for t, info in THEME_INFO.items()
    ]


def product_photo(product, small: bool = False) -> str:
    """직원이 올린 사진, 없으면 테마 기본 사진."""
    if getattr(product, "image", None):
        return product.image.url
    return photo_url(THEME_INFO[Theme(product.theme)]["photo"], small=small)
