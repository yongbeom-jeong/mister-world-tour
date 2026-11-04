"""화면용 템플릿 도구: {% load mwt %}"""

from django import template

from apps.travel import display

register = template.Library()


@register.filter
def product_photo(product, size: str = ""):
    """{{ product|product_photo }} 또는 {{ product|product_photo:"sm" }}"""
    return display.product_photo(product, small=(size == "sm"))


@register.filter
def won(value):
    """1234000 → '1,234,000원'"""
    try:
        return f"{int(value):,}원"
    except (TypeError, ValueError):
        return value


@register.filter
def theme_info(code, key: str):
    """{{ product.theme|theme_info:"en" }}"""
    info = display.THEME_INFO.get(code)
    return info.get(key, "") if info else ""


@register.simple_tag
def photo(name: str, size: str = ""):
    """{% photo "hero" %} → 정적 사진 경로"""
    return display.photo_url(name, small=(size == "sm"))


@register.filter
def get_item(mapping, key):
    """{{ dict|get_item:key }}"""
    try:
        return mapping.get(key)
    except AttributeError:
        return None
