package com.misterworldtour.travel;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 확정 업무 정책의 숫자·허용값. 바꿀 때는 AGENTS.md 와 함께. */
public final class TravelRules {

    /** 허니문: 신청마다 정확히 2명. */
    public static final int HONEYMOON_PARTY_SIZE = 2;
    /** 그 외: 상품 전체 정원. */
    public static final int GROUP_CAPACITY = 10;
    /** 그 외: 실제 여행자 합계 3명 이상이면 출발 확정. */
    public static final int GROUP_MIN_DEPARTURE = 3;

    /** 단골: 최근 3년 내 완료 여행 3회 이상이면 10% 할인. */
    public static final int REGULAR_MIN_TRIPS = 3;
    public static final int REGULAR_YEARS = 3;
    public static final int REGULAR_DISCOUNT_PERCENT = 10;

    /** 허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만. */
    public static final Set<TourLevel> ALLOWED_LEVELS_FOR_RESTRICTED = EnumSet.of(TourLevel.GRAND, TourLevel.PREMIUM);

    /** 낮은 것부터 높은 순서 (등급 비교용). */
    public static final List<HotelLevel> HOTEL_ORDER = List.of(HotelLevel.values());
    public static final List<MealOption> MEAL_ORDER = List.of(MealOption.values());
    /** 허니문·효도는 고객 옵션도 그랜드 기본(4등급 호텔·현지식) 아래로는 고를 수 없다. */
    public static final HotelLevel RESTRICTED_MIN_HOTEL = HotelLevel.STAR4;
    public static final MealOption RESTRICTED_MIN_MEAL = MealOption.LOCAL;

    /** 음료: 도시락에는 추가 불가, 고급 식사에는 샴페인이 기본 포함. */
    public static final Set<MealOption> NO_DRINK_MEALS = EnumSet.of(MealOption.LUNCHBOX);
    public static final Map<MealOption, Set<DrinkOption>> INCLUDED_DRINKS =
            Map.of(MealOption.FINE, EnumSet.of(DrinkOption.CHAMPAGNE));

    private TravelRules() {
    }

    /** 테마별로 고를 수 있는 교통. */
    public static Set<TransportOption> allowedTransports(Theme theme) {
        return theme == Theme.HONEYMOON
                ? EnumSet.of(TransportOption.LUXURY_CAR_2, TransportOption.LIMOUSINE)
                : EnumSet.of(TransportOption.LUXURY_VAN_10, TransportOption.LIMOUSINE);
    }

    public static Set<DrinkOption> includedDrinks(MealOption meal) {
        return INCLUDED_DRINKS.getOrDefault(meal, EnumSet.noneOf(DrinkOption.class));
    }
}
