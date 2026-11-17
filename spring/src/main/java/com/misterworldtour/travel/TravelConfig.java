package com.misterworldtour.travel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 호텔·교통·식사·음료 구성 (설계 05장 TravelConfiguration). 화면·세션과 주고받는 값이라 문자열(enum 이름)로 둔다.
 * 상품의 기본 구성과 신청용 변경 구성은 별개의 값이다. 변경이 상품을 덮어쓰지 않는다.
 * 유효성은 {@link ConfigurationRules#validate} 로 검사한다.
 */
public record TravelConfig(String hotelLevel, String transport, String meal, List<String> drinks)
        implements Serializable {

    public static final String HOTEL = "hotel_level";
    public static final String TRANSPORT = "transport";
    public static final String MEAL = "meal";
    public static final String DRINKS = "drinks";
    public static final List<String> FIELDS = List.of(HOTEL, TRANSPORT, MEAL, DRINKS);

    public TravelConfig {
        drinks = normalizeDrinks(drinks);
    }

    /** 상품의 저장된 기본 구성 (음료 없음). */
    public static TravelConfig defaultOf(TravelProduct product) {
        return new TravelConfig(product.getHotelLevel().name(), product.getTransport().name(),
                product.getMeal().name(), List.of());
    }

    /** 음료 목록 정리: 빈 값 제거, 중복 제거, 정렬. 쉼표로 이은 문자열도 받는다. */
    public static List<String> normalizeDrinks(Object value) {
        TreeSet<String> set = new TreeSet<>();
        if (value instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    set.add(part.strip());
                }
            }
        } else if (value instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !o.toString().isBlank()) {
                    set.add(o.toString().strip());
                }
            }
        }
        return List.copyOf(set);
    }

    /** 화면·JSON 용 (Django 판과 같은 키: hotel_level·transport·meal·drinks). */
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
}
