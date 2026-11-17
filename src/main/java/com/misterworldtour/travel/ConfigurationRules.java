package com.misterworldtour.travel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 여행 구성 규칙 (설계 05장 TravelConfiguration, Django 판 travel/configuration.py).
 * {@link #applyChanges} 는 실패하면 예외를 내고 전달받은 직전 구성은 그대로 둔다.
 */
public final class ConfigurationRules {

    /** 구성 검사 실패. errors 는 항목(hotel_level 등)별 안내. */
    public static class ConfigurationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public ConfigurationException(Map<String, String> errors) {
            super(String.join("; ", errors.values()));
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    private ConfigurationRules() {
    }

    /** 고객이 고를 수 있는 호텔. 허니문·효도는 4등급 이상 (그랜드 등급 이상만). */
    public static List<HotelLevel> customerHotelChoices(Theme theme) {
        List<HotelLevel> all = TravelRules.HOTEL_ORDER;
        return theme.isLevelRestricted() ? all.subList(all.indexOf(TravelRules.RESTRICTED_MIN_HOTEL), all.size()) : all;
    }

    /** 고객이 고를 수 있는 식사. 허니문·효도는 현지식 이상. */
    public static List<MealOption> customerMealChoices(Theme theme) {
        List<MealOption> all = TravelRules.MEAL_ORDER;
        return theme.isLevelRestricted() ? all.subList(all.indexOf(TravelRules.RESTRICTED_MIN_MEAL), all.size()) : all;
    }

    /** 고객이 고를 수 있는 교통 (enum 순서). */
    public static List<TransportOption> customerTransportChoices(Theme theme) {
        Set<TransportOption> allowed = TravelRules.allowedTransports(theme);
        return Arrays.stream(TransportOption.values()).filter(allowed::contains).toList();
    }

    /** 문제가 있는 항목별 안내. 비어 있으면 유효. */
    public static Map<String, String> validate(TravelProduct product, TravelConfig config) {
        Map<String, String> errors = new LinkedHashMap<>();
        Theme theme = product.getTheme();
        String restricted = theme.isLevelRestricted() ? theme.getLabel() : null;

        HotelLevel hotel = parse(HotelLevel.class, config.hotelLevel());
        if (hotel == null) {
            errors.put(TravelConfig.HOTEL, "호텔 등급을 다시 선택해 주세요.");
        } else if (!customerHotelChoices(theme).contains(hotel)) {
            errors.put(TravelConfig.HOTEL, restricted + "는 그랜드 등급 이상이라 4등급 이상 호텔만 선택할 수 있습니다.");
        }
        MealOption meal = parse(MealOption.class, config.meal());
        if (meal == null) {
            errors.put(TravelConfig.MEAL, "식사 구성을 다시 선택해 주세요.");
        } else if (!customerMealChoices(theme).contains(meal)) {
            errors.put(TravelConfig.MEAL, restricted + "는 그랜드 등급 이상이라 현지식 이상 식사만 선택할 수 있습니다.");
        }
        TransportOption transport = parse(TransportOption.class, config.transport());
        if (transport == null) {
            errors.put(TravelConfig.TRANSPORT, "교통 구성을 다시 선택해 주세요.");
        } else if (!TravelRules.allowedTransports(theme).contains(transport)) {
            errors.put(TravelConfig.TRANSPORT,
                    theme.getLabel() + "에서는 '" + transport.getLabel() + "'을(를) 선택할 수 없습니다.");
        }
        if (config.drinks().stream().anyMatch(d -> parse(DrinkOption.class, d) == null)) {
            errors.put(TravelConfig.DRINKS, "선택할 수 없는 음료가 있습니다.");
        } else if (!config.drinks().isEmpty() && meal != null && TravelRules.NO_DRINK_MEALS.contains(meal)) {
            errors.put(TravelConfig.DRINKS, "도시락 구성에는 음료를 추가할 수 없습니다. 식사를 먼저 바꿔 주세요.");
        }
        return errors;
    }

    /**
     * 현재 구성에 바뀐 항목만 적용한 새 구성을 만든다. current·changes 는 화면에서 온 값(키: hotel_level 등).
     * 검증 실패 시 {@link ConfigurationException}.
     */
    public static TravelConfig applyChanges(TravelProduct product, Map<String, ?> current, Map<String, ?> changes) {
        TravelConfig base = TravelConfig.defaultOf(product);
        Map<String, Object> merged = new LinkedHashMap<>(base.toMap());
        for (String key : TravelConfig.FIELDS) {
            if (current != null && current.containsKey(key)) {
                merged.put(key, current.get(key));
            }
        }
        for (String key : TravelConfig.FIELDS) {
            if (changes != null && changes.get(key) != null) {
                merged.put(key, changes.get(key));
            }
        }
        TravelConfig config = new TravelConfig(str(merged.get(TravelConfig.HOTEL)), str(merged.get(TravelConfig.TRANSPORT)),
                str(merged.get(TravelConfig.MEAL)), TravelConfig.normalizeDrinks(merged.get(TravelConfig.DRINKS)));
        Map<String, String> errors = validate(product, config);
        if (!errors.isEmpty()) {
            throw new ConfigurationException(errors);
        }
        return config;
    }

    /** 화면 표시용 이름 (유효한 구성만). */
    public static Map<String, String> describe(TravelConfig config) {
        MealOption meal = MealOption.valueOf(config.meal());
        Set<DrinkOption> included = TravelRules.includedDrinks(meal);
        List<String> drinks = new ArrayList<>();
        for (DrinkOption d : DrinkOption.values()) {
            if (included.contains(d)) {
                drinks.add(d.getLabel() + "(식사 포함)");
            }
        }
        for (String code : config.drinks()) {
            DrinkOption d = DrinkOption.valueOf(code);
            if (!included.contains(d)) {
                drinks.add(d.getLabel());
            }
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put(TravelConfig.HOTEL, HotelLevel.valueOf(config.hotelLevel()).getLabel());
        m.put(TravelConfig.TRANSPORT, TransportOption.valueOf(config.transport()).getLabel());
        m.put(TravelConfig.MEAL, meal.getLabel());
        m.put(TravelConfig.DRINKS, drinks.isEmpty() ? "없음" : String.join(", ", drinks));
        return m;
    }

    // ---------------------------------------------------------------- 상품 기본 구성 (직원, UC6 (3))

    /** 직원이 상품 기본 구성으로 고를 수 있는 값. 테마·등급의 기본값이 맨 앞(=기본)이다. */
    public record BaseOptions(List<HotelLevel> hotels, List<MealOption> meals, List<TransportOption> transports) {
    }

    /** 호텔·식사는 등급 기본값 이상만 (프리미엄인데 도시락 같은 구성 방지), 교통은 테마에서 허용한 것만. */
    public static BaseOptions baseOptionChoices(Theme theme, TourLevel level) {
        List<HotelLevel> hotels = TravelRules.HOTEL_ORDER;
        List<MealOption> meals = TravelRules.MEAL_ORDER;
        List<TransportOption> transports = new ArrayList<>();
        transports.add(theme.getDefaultTransport());
        for (TransportOption t : TransportOption.values()) {
            if (TravelRules.allowedTransports(theme).contains(t) && t != theme.getDefaultTransport()) {
                transports.add(t);
            }
        }
        return new BaseOptions(hotels.subList(hotels.indexOf(level.getDefaultHotel()), hotels.size()),
                meals.subList(meals.indexOf(level.getDefaultMeal()), meals.size()), transports);
    }

    /** 직원이 고른 기본 구성 검사. null(비어 있음)은 기본값을 쓰므로 검사하지 않는다. */
    public static Map<String, String> baseConfigurationErrors(Theme theme, TourLevel level, HotelLevel hotel,
                                                              MealOption meal, TransportOption transport) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (theme == null || level == null) {
            return errors;
        }
        BaseOptions allowed = baseOptionChoices(theme, level);
        if (hotel != null && !allowed.hotels().contains(hotel)) {
            errors.put(TravelConfig.HOTEL, level.getLabel() + " 등급의 기본 호텔은 "
                    + allowed.hotels().get(0).getLabel() + " 이상이어야 합니다.");
        }
        if (meal != null && !allowed.meals().contains(meal)) {
            errors.put(TravelConfig.MEAL, level.getLabel() + " 등급의 기본 식사는 "
                    + allowed.meals().get(0).getLabel() + " 이상이어야 합니다.");
        }
        if (transport != null && !allowed.transports().contains(transport)) {
            errors.put(TravelConfig.TRANSPORT, theme.getLabel() + "에서는 선택할 수 없는 교통입니다.");
        }
        return errors;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) {
                return e;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
