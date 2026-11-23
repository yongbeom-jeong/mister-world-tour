package com.misterworldtour.travel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 여행 구성: 호텔·교통·식사·음료 (설계 5.1 TravelConfiguration). 화면·세션과 주고받는 값이라 enum 이름 문자열로 둔다.
 * {@link #applyChanges} 는 새 구성을 만들 뿐 상품의 기본 구성을 덮어쓰지 않는다.
 * 음료는 신청 전체 기준 수량이며, 같은 코드를 수량만큼 담는다 (예: 커피 2포트 = ["COFFEE", "COFFEE"]).
 */
public record TravelConfiguration(String hotelLevel, String transport, String meal, List<String> drinks)
        implements Serializable {

    public static final String HOTEL = "hotel_level";
    public static final String TRANSPORT = "transport";
    public static final String MEAL = "meal";
    public static final String DRINKS = "drinks";
    public static final List<String> FIELDS = List.of(HOTEL, TRANSPORT, MEAL, DRINKS);
    /** 음료 종류마다 고를 수 있는 최대 수량. */
    public static final int MAX_DRINK_QUANTITY = 5;

    public TravelConfiguration {
        drinks = normalizeDrinks(drinks);
    }

    /** 테마·투어 등급의 기본 구성 (테마 기본 교통, 등급 기본 호텔·식사, 음료 없음). */
    public TravelConfiguration(Theme theme, TourLevel level) {
        this(level.getDefaultHotel().name(), theme.getDefaultTransport().name(), level.getDefaultMeal().name(), List.of());
    }

    /** 상품에 저장된 기본 구성 (음료 없음). */
    public static TravelConfiguration defaultOf(TravelProduct product) {
        return new TravelConfiguration(product.getHotelLevel().name(), product.getTransport().name(),
                product.getMeal().name(), List.of());
    }

    /** 바뀐 항목만 적용한 새 구성. 키는 hotel_level·transport·meal·drinks, 값이 null 인 항목은 그대로 둔다. 검사는 하지 않는다. */
    public TravelConfiguration applyChanges(Map<String, ?> optionChanges) {
        Map<String, Object> merged = new LinkedHashMap<>(toMap());
        for (String key : FIELDS) {
            if (optionChanges != null && optionChanges.get(key) != null) {
                merged.put(key, optionChanges.get(key));
            }
        }
        return new TravelConfiguration(str(merged.get(HOTEL)), str(merged.get(TRANSPORT)), str(merged.get(MEAL)),
                normalizeDrinks(merged.get(DRINKS)));
    }

    /** 고객이 고른 구성이 이 상품에서 쓸 수 있는지 (설계 ValidateConfiguration). 문제 항목별 안내, 비어 있으면 유효. */
    public Map<String, String> validateConfiguration(TravelProduct product) {
        Map<String, String> errors = new LinkedHashMap<>();
        Theme theme = product.getTheme();
        String restricted = theme.isLevelRestricted() ? theme.getLabel() : null;

        HotelLevel hotel = parse(HotelLevel.class, hotelLevel);
        if (hotel == null) {
            errors.put(HOTEL, "호텔 등급을 다시 선택해 주세요.");
        } else if (!customerHotelChoices(theme).contains(hotel)) {
            errors.put(HOTEL, restricted + "는 그랜드 등급 이상이라 4등급 이상 호텔만 선택할 수 있습니다.");
        }
        MealOption m = parse(MealOption.class, meal);
        if (m == null) {
            errors.put(MEAL, "식사 구성을 다시 선택해 주세요.");
        } else if (!customerMealChoices(theme).contains(m)) {
            errors.put(MEAL, restricted + "는 그랜드 등급 이상이라 현지식 이상 식사만 선택할 수 있습니다.");
        }
        TransportOption t = parse(TransportOption.class, transport);
        if (t == null) {
            errors.put(TRANSPORT, "교통 구성을 다시 선택해 주세요.");
        } else if (!TravelRules.allowedTransports(theme).contains(t)) {
            errors.put(TRANSPORT, theme.getLabel() + "에서는 '" + t.getLabel() + "'을(를) 선택할 수 없습니다.");
        }
        if (drinks.stream().anyMatch(d -> parse(DrinkOption.class, d) == null)) {
            errors.put(DRINKS, "선택할 수 없는 음료가 있습니다.");
        } else if (drinkQuantities().values().stream().anyMatch(q -> q > MAX_DRINK_QUANTITY)) {
            errors.put(DRINKS, "음료는 종류마다 최대 " + MAX_DRINK_QUANTITY + "개까지 추가할 수 있습니다.");
        } else if (!drinks.isEmpty() && m != null && TravelRules.NO_DRINK_MEALS.contains(m)) {
            errors.put(DRINKS, "도시락 구성에는 음료를 추가할 수 없습니다. 식사를 먼저 바꿔 주세요.");
        }
        return errors;
    }

    /** 직원이 정한 상품 기본 구성이 테마·등급에 맞는지 (UC6 (3)). 문제 항목별 안내, 비어 있으면 유효. */
    public Map<String, String> validateConfiguration(Theme theme, TourLevel level) {
        Map<String, String> errors = new LinkedHashMap<>();
        BaseOptions allowed = baseOptionChoices(theme, level);
        HotelLevel hotel = parse(HotelLevel.class, hotelLevel);
        MealOption m = parse(MealOption.class, meal);
        TransportOption t = parse(TransportOption.class, transport);
        if (hotel == null || !allowed.hotels().contains(hotel)) {
            errors.put(HOTEL, level.getLabel() + " 등급의 기본 호텔은 " + allowed.hotels().get(0).getLabel()
                    + " 이상이어야 합니다.");
        }
        if (m == null || !allowed.meals().contains(m)) {
            errors.put(MEAL, level.getLabel() + " 등급의 기본 식사는 " + allowed.meals().get(0).getLabel()
                    + " 이상이어야 합니다.");
        }
        if (t == null || !allowed.transports().contains(t)) {
            errors.put(TRANSPORT, theme.getLabel() + "에서는 선택할 수 없는 교통입니다.");
        }
        return errors;
    }

    /** 고객이 고를 수 있는 호텔. 허니문·효도는 4등급 이상. */
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

    /** 직원이 상품 기본 구성으로 고를 수 있는 값. 테마·등급의 기본값이 맨 앞(=기본)이다. */
    public record BaseOptions(List<HotelLevel> hotels, List<MealOption> meals, List<TransportOption> transports) {
    }

    /** 호텔·식사는 등급 기본값 이상, 교통은 테마에서 허용한 것만. */
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

    /** 음료 코드별 수량 (음료 순서, 고른 것만). */
    public Map<String, Integer> drinkQuantities() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (DrinkOption d : DrinkOption.values()) {
            int q = Collections.frequency(drinks, d.name());
            if (q > 0) {
                out.put(d.name(), q);
            }
        }
        for (String code : drinks) { // 알 수 없는 코드도 검사에서 잡히도록 남긴다
            out.putIfAbsent(code, Collections.frequency(drinks, code));
        }
        return out;
    }

    /** 화면 표시용 이름 (유효한 구성만). 예: "샴페인(식사 포함), 스페셜티 커피 2포트". */
    public Map<String, String> describe() {
        MealOption m = MealOption.valueOf(meal);
        Set<DrinkOption> included = TravelRules.includedDrinks(m);
        List<String> names = new ArrayList<>();
        for (DrinkOption d : DrinkOption.values()) {
            if (included.contains(d)) {
                names.add(d.getLabel() + "(식사 포함)");
            }
        }
        drinkQuantities().forEach((code, q) -> {
            DrinkOption d = DrinkOption.valueOf(code);
            names.add(d.getLabel() + " " + q + d.getUnit() + (included.contains(d) ? " 추가" : ""));
        });
        Map<String, String> out = new LinkedHashMap<>();
        out.put(HOTEL, HotelLevel.valueOf(hotelLevel).getLabel());
        out.put(TRANSPORT, TransportOption.valueOf(transport).getLabel());
        out.put(MEAL, m.getLabel());
        out.put(DRINKS, names.isEmpty() ? "없음" : String.join(", ", names));
        return out;
    }

    /** 화면·JSON 용 (키: hotel_level·transport·meal·drinks). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(HOTEL, hotelLevel);
        m.put(TRANSPORT, transport);
        m.put(MEAL, meal);
        m.put(DRINKS, new ArrayList<>(drinks));
        return m;
    }

    public String drinksCsv() {
        return String.join(",", drinks);
    }

    /**
     * 음료 목록 정리: 빈 값 제거, 정렬. 같은 코드가 여러 번 있으면 그 수량이다.
     * 쉼표로 이은 문자열("COFFEE,COFFEE")과 코드→수량 맵({"COFFEE": 2})도 받는다.
     */
    public static List<String> normalizeDrinks(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.strip());
                }
            }
        } else if (value instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !o.toString().isBlank()) {
                    list.add(o.toString().strip());
                }
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                int q = quantity(e.getValue());
                for (int i = 0; i < q && e.getKey() != null; i++) {
                    list.add(e.getKey().toString().strip());
                }
            }
        }
        Collections.sort(list);
        return List.copyOf(list);
    }

    /** 맵으로 받은 수량. 숫자가 아니면 0, 지나치게 큰 값은 검사에서 걸리도록 한도+1 로 자른다. */
    private static int quantity(Object v) {
        try {
            int q = v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).strip());
            return Math.max(0, Math.min(q, MAX_DRINK_QUANTITY + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** enum 이름 문자열을 값으로. 없거나 잘못된 값이면 null. */
    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
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
