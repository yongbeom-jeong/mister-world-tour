"""여행 구성 (설계 05장 TravelConfiguration).

구성은 dict 로 다룬다: {"hotel_level", "transport", "meal", "drinks": [음료 코드...]}
- 상품의 저장된 기본 구성과 신청용 변경 구성은 별개의 값이다. 변경이 상품을 덮어쓰지 않는다.
- apply_changes 는 실패하면 예외를 내고 전달받은 직전 구성은 그대로 둔다.
"""

from .choices import (
    ALLOWED_TRANSPORTS,
    HOTEL_ORDER,
    INCLUDED_DRINKS,
    LEVEL_DEFAULTS,
    MEAL_ORDER,
    NO_DRINK_MEALS,
    RESTRICTED_LEVEL_THEMES,
    RESTRICTED_MIN_HOTEL,
    RESTRICTED_MIN_MEAL,
    THEME_DEFAULTS,
    DrinkOption,
    HotelLevel,
    MealOption,
    Theme,
    TourLevel,
    TransportOption,
)

FIELDS = ("hotel_level", "transport", "meal", "drinks")


class ConfigurationError(Exception):
    def __init__(self, errors: dict[str, str]):
        super().__init__("; ".join(errors.values()))
        self.errors = errors


def default_configuration(product) -> dict:
    return {"hotel_level": product.hotel_level, "transport": product.transport, "meal": product.meal, "drinks": []}


def normalize_drinks(value) -> list[str]:
    if not value:
        return []
    if isinstance(value, str):
        value = value.split(",")
    return sorted({v.strip() for v in value if v and v.strip()})


def customer_hotel_choices(theme: str) -> list[str]:
    """고객이 고를 수 있는 호텔. 허니문·효도는 4등급 이상 (그랜드 등급 이상만)."""
    return HOTEL_ORDER[HOTEL_ORDER.index(RESTRICTED_MIN_HOTEL):] if theme in RESTRICTED_LEVEL_THEMES else list(HOTEL_ORDER)


def customer_meal_choices(theme: str) -> list[str]:
    """고객이 고를 수 있는 식사. 허니문·효도는 현지식 이상."""
    return MEAL_ORDER[MEAL_ORDER.index(RESTRICTED_MIN_MEAL):] if theme in RESTRICTED_LEVEL_THEMES else list(MEAL_ORDER)


def validate_configuration(product, config: dict) -> dict[str, str]:
    """문제가 있는 항목별 안내. 비어 있으면 유효."""
    errors = {}
    restricted = Theme(product.theme).label if product.theme in RESTRICTED_LEVEL_THEMES else None
    if config.get("hotel_level") not in HotelLevel.values:
        errors["hotel_level"] = "호텔 등급을 다시 선택해 주세요."
    elif config["hotel_level"] not in customer_hotel_choices(product.theme):
        errors["hotel_level"] = f"{restricted}는 그랜드 등급 이상이라 4등급 이상 호텔만 선택할 수 있습니다."
    if config.get("meal") not in MealOption.values:
        errors["meal"] = "식사 구성을 다시 선택해 주세요."
    elif config["meal"] not in customer_meal_choices(product.theme):
        errors["meal"] = f"{restricted}는 그랜드 등급 이상이라 현지식 이상 식사만 선택할 수 있습니다."
    transport = config.get("transport")
    if transport not in TransportOption.values:
        errors["transport"] = "교통 구성을 다시 선택해 주세요."
    elif transport not in ALLOWED_TRANSPORTS[Theme(product.theme)]:
        errors["transport"] = f"{Theme(product.theme).label}에서는 '{TransportOption(transport).label}'을(를) 선택할 수 없습니다."
    drinks = config.get("drinks", [])
    if any(d not in DrinkOption.values for d in drinks):
        errors["drinks"] = "선택할 수 없는 음료가 있습니다."
    elif drinks and config.get("meal") in NO_DRINK_MEALS:
        errors["drinks"] = "도시락 구성에는 음료를 추가할 수 없습니다. 식사를 먼저 바꿔 주세요."
    return errors


def apply_changes(product, current: dict, changes: dict) -> dict:
    """현재 구성에 바뀐 항목만 적용한 새 구성을 만든다. 검증 실패 시 ConfigurationError."""
    new = {**default_configuration(product), **{k: v for k, v in current.items() if k in FIELDS}}
    for key in FIELDS:
        if key in changes and changes[key] is not None:
            new[key] = normalize_drinks(changes[key]) if key == "drinks" else changes[key]
    new["drinks"] = normalize_drinks(new.get("drinks"))
    errors = validate_configuration(product, new)
    if errors:
        raise ConfigurationError(errors)
    return new


def describe(config: dict) -> dict[str, str]:
    """화면 표시용 이름."""
    included = INCLUDED_DRINKS.get(config["meal"], set())
    drinks = [DrinkOption(d).label for d in config.get("drinks", [])]
    if included:
        drinks = [f"{DrinkOption(d).label}(식사 포함)" for d in sorted(included)] + [
            DrinkOption(d).label for d in config.get("drinks", []) if d not in included
        ]
    return {
        "hotel_level": HotelLevel(config["hotel_level"]).label,
        "transport": TransportOption(config["transport"]).label,
        "meal": MealOption(config["meal"]).label,
        "drinks": ", ".join(drinks) if drinks else "없음",
    }


# ---------------------------------------------------------------- 상품 기본 구성 (직원, UC6 (3))

def base_option_choices(theme: str, level: str) -> dict[str, list[str]]:
    """직원이 상품 기본 구성으로 고를 수 있는 값. 테마·등급의 기본값이 맨 앞(=기본)이다.

    호텔·식사는 등급 기본값 이상만 (프리미엄인데 도시락 같은 구성 방지), 교통은 테마에서 허용한 것만.
    """
    defaults = LEVEL_DEFAULTS[TourLevel(level)]
    theme_default = THEME_DEFAULTS[Theme(theme)]["transport"]
    transports = [theme_default] + [t for t in TransportOption.values
                                    if t in ALLOWED_TRANSPORTS[Theme(theme)] and t != theme_default]
    return {
        "hotel_level": HOTEL_ORDER[HOTEL_ORDER.index(defaults["hotel"]):],
        "meal": MEAL_ORDER[MEAL_ORDER.index(defaults["meal"]):],
        "transport": transports,
    }


def base_configuration_errors(theme: str, level: str, config: dict) -> dict[str, str]:
    """직원이 고른 기본 구성 검사. 비어 있는 항목은 기본값을 쓰므로 검사하지 않는다."""
    if theme not in Theme.values or level not in TourLevel.values:
        return {}
    allowed = base_option_choices(theme, level)
    messages = {
        "hotel_level": f"{TourLevel(level).label} 등급의 기본 호텔은 "
                       f"{HotelLevel(allowed['hotel_level'][0]).label} 이상이어야 합니다.",
        "meal": f"{TourLevel(level).label} 등급의 기본 식사는 {MealOption(allowed['meal'][0]).label} 이상이어야 합니다.",
        "transport": f"{Theme(theme).label}에서는 선택할 수 없는 교통입니다.",
    }
    return {k: messages[k] for k in ("hotel_level", "meal", "transport")
            if config.get(k) and config[k] not in allowed[k]}
