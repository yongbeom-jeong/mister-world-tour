package com.misterworldtour.travel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 가격·단골 할인 계산 (설계 05장 TravelPriceCalculator, Django 판 travel/pricing.py).
 *
 * <pre>
 * 할인 전 전체 금액 = (1인 기본 가격 + 옵션 추가금) × 실제 여행자 수
 *   옵션 추가금은 기본보다 높이면 +, 낮추면 − (차액 반영). 1인 가격은 기본 가격의 50% 아래로 내려가지 않는다.
 * 단골 할인액     = 단골이면 할인 전 금액의 10% (원 미만 버림), 아니면 0
 * 최종 신청 금액   = 할인 전 금액 − 단골 할인액
 * </pre>
 */
public final class Pricing {

    /** 1인당 옵션 금액(원). 설계상 금액은 후속 확정 → 임시 금액. 기본 구성에서 바꾼 항목만 차액을 반영한다. */
    public static final Map<String, Map<String, Integer>> OPTION_PRICE_TABLE = Map.of(
            TravelConfig.HOTEL, Map.of("STAR3", 0, "STAR4", 150_000, "STAR5", 350_000),
            TravelConfig.TRANSPORT, Map.of("LUXURY_VAN_10", 0, "LUXURY_CAR_2", 0, "LIMOUSINE", 150_000),
            TravelConfig.MEAL, Map.of("LUNCHBOX", 0, "LOCAL", 80_000, "FINE", 200_000));

    /** 옵션을 아무리 낮춰도 1인 가격은 기본 가격의 50% 이상 (임시 정책). */
    public static final double MIN_UNIT_PRICE_RATIO = 0.5;

    /** 추가 음료 1인 금액(원). 식사에 기본 포함된 음료는 0원 (기본 제공분 중복 과금 방지). */
    public static final Map<String, Integer> DRINK_PRICES =
            Map.of("WINE", 50_000, "BEER", 20_000, "CHAMPAGNE", 60_000, "COFFEE", 10_000);

    /** 신청 금액 계산 결과. 검토 때와 등록 때 값이 같아야 신청한다 (equals 로 비교). */
    public record PriceBreakdown(int unitPrice, int travelerCount, int subtotal, boolean regular, int discountAmount,
                                 int finalAmount) implements Serializable {
    }

    private Pricing() {
    }

    private static String productValue(TravelProduct product, String field) {
        return switch (field) {
            case TravelConfig.HOTEL -> product.getHotelLevel().name();
            case TravelConfig.TRANSPORT -> product.getTransport().name();
            case TravelConfig.MEAL -> product.getMeal().name();
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** 기본 구성 대비 한 항목의 1인 금액 차이. 높이면 +, 낮추면 −, 같으면 0. */
    public static int optionDifference(TravelProduct product, String field, String chosen) {
        Map<String, Integer> table = OPTION_PRICE_TABLE.get(field);
        return table.getOrDefault(chosen, 0) - table.getOrDefault(productValue(product, field), 0);
    }

    /** 기본 구성과 다른 항목의 차액 합계 + 추가 음료 (기본 제공 음료는 중복 과금하지 않음). */
    public static int optionSurcharge(TravelProduct product, TravelConfig config) {
        int total = optionDifference(product, TravelConfig.HOTEL, config.hotelLevel())
                + optionDifference(product, TravelConfig.TRANSPORT, config.transport())
                + optionDifference(product, TravelConfig.MEAL, config.meal());
        Set<String> included = TravelRules.includedDrinks(MealOption.valueOf(config.meal())).stream()
                .map(Enum::name).collect(Collectors.toSet());
        for (String d : config.drinks()) {
            if (!included.contains(d)) {
                total += DRINK_PRICES.getOrDefault(d, 0);
            }
        }
        return total;
    }

    /** 1인 가격 = 1인 기본 가격 + 옵션 추가금(음수 가능), 하한은 기본 가격의 50%. */
    public static int calculateUnitPrice(TravelProduct product, TravelConfig config) {
        int floor = (int) (product.getBasePrice() * MIN_UNIT_PRICE_RATIO);
        return Math.max(product.getBasePrice() + optionSurcharge(product, config), floor);
    }

    /** 최근 3년 이내 완료 여행이 3회 이상인지. 같은 완료 여행 식별값은 한 번만 센다. */
    public static boolean isRegularCustomer(Iterable<TravelHistory> histories, LocalDate applicationDate) {
        Set<String> trips = new java.util.HashSet<>();
        for (TravelHistory h : histories) {
            if (h.isCompletedWithinThreeYears(applicationDate)) {
                trips.add(h.getCompletedTravelId());
            }
        }
        return trips.size() >= TravelRules.REGULAR_MIN_TRIPS;
    }

    public static PriceBreakdown calculateApplicationPrice(int unitPrice, int travelerCount,
                                                           Iterable<TravelHistory> histories, LocalDate applicationDate) {
        int subtotal = unitPrice * travelerCount;
        boolean regular = isRegularCustomer(histories, applicationDate);
        // 원 미만 버림. 곱셈이 int 범위를 넘지 않게 long 으로 계산
        int discount = regular ? (int) ((long) subtotal * TravelRules.REGULAR_DISCOUNT_PERCENT / 100) : 0;
        return new PriceBreakdown(unitPrice, travelerCount, subtotal, regular, discount, subtotal - discount);
    }
}
