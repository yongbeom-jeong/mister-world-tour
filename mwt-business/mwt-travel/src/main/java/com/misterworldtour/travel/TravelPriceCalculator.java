package com.misterworldtour.travel;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 가격·단골 할인 계산 (설계 5.1 TravelPriceCalculator). */
public final class TravelPriceCalculator {

    /** 1인당 옵션 금액(원). 기본 구성에서 바꾼 항목만 차액을 반영한다. */
    public static final Map<String, Map<String, Integer>> OPTION_PRICE_TABLE = Map.of(
            TravelConfiguration.HOTEL, Map.of("STAR3", 0, "STAR4", 150_000, "STAR5", 350_000),
            TravelConfiguration.TRANSPORT, Map.of("LUXURY_VAN_10", 0, "LUXURY_CAR_2", 0, "LIMOUSINE", 150_000),
            TravelConfiguration.MEAL, Map.of("LUNCHBOX", 0, "LOCAL", 80_000, "FINE", 200_000));

    /** 추가 음료 1개(병·포트·세트) 금액(원). 신청 전체 기준 수량만큼 더한다. 식사 포함 음료는 따로 0원으로 제공. */
    public static final Map<String, Integer> DRINK_PRICES =
            Map.of("WINE", 50_000, "BEER", 20_000, "CHAMPAGNE", 60_000, "COFFEE", 10_000);

    /** 1인 가격 하한: 기본 가격의 50%. */
    public static final double MIN_UNIT_PRICE_RATIO = 0.5;

    /** 신청 금액 계산 결과. subtotal = 1인 가격 × 인원 + 추가 음료. 검토 때와 등록 때 값이 같아야 신청한다 (equals 로 비교). */
    public record PriceBreakdown(int unitPrice, int travelerCount, int subtotal, boolean regular, int discountAmount,
                                 int finalAmount) implements Serializable {

        /** 추가 음료 금액 (신청 전체). */
        public int drinkPrice() {
            return subtotal - unitPrice * travelerCount;
        }
    }

    private TravelPriceCalculator() {
    }

    private static String productValue(TravelProduct product, String field) {
        return switch (field) {
            case TravelConfiguration.HOTEL -> product.getHotelLevel().name();
            case TravelConfiguration.TRANSPORT -> product.getTransport().name();
            case TravelConfiguration.MEAL -> product.getMeal().name();
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** 기본 구성 대비 한 항목의 1인 금액 차이. 높이면 +, 낮추면 −, 같으면 0. */
    public static int optionDifference(TravelProduct product, String field, String chosen) {
        Map<String, Integer> table = OPTION_PRICE_TABLE.get(field);
        return table.getOrDefault(chosen, 0) - table.getOrDefault(productValue(product, field), 0);
    }

    /** 기본 구성과 다른 항목(호텔·교통·식사)의 1인 차액 합계. */
    static int optionSurcharge(TravelProduct product, TravelConfiguration configuration) {
        return optionDifference(product, TravelConfiguration.HOTEL, configuration.hotelLevel())
                + optionDifference(product, TravelConfiguration.TRANSPORT, configuration.transport())
                + optionDifference(product, TravelConfiguration.MEAL, configuration.meal());
    }

    /** 추가 음료 금액 (신청 전체): 고른 수량 × 1개 금액. */
    public static int calculateDrinkPrice(TravelConfiguration configuration) {
        int total = 0;
        for (String d : configuration.drinks()) {
            total += DRINK_PRICES.getOrDefault(d, 0);
        }
        return total;
    }

    /** 1인 가격 = 1인 기본 가격 + 옵션 추가금(음수 가능), 하한은 기본 가격의 50%. 음료는 포함하지 않는다. */
    public static int calculateUnitPrice(TravelProduct product, TravelConfiguration configuration) {
        int floor = (int) (product.getBasePrice() * MIN_UNIT_PRICE_RATIO);
        return Math.max(product.getBasePrice() + optionSurcharge(product, configuration), floor);
    }

    /** 최근 3년 이내 완료 여행이 3회 이상인지. 같은 완료 여행 식별값은 한 번만 센다. */
    public static boolean isRegularCustomer(Iterable<TravelHistory> histories, LocalDate applicationDate) {
        Set<String> trips = new HashSet<>();
        for (TravelHistory h : histories) {
            if (h.isCompletedWithinThreeYears(applicationDate)) {
                trips.add(h.getCompletedTravelId());
            }
        }
        return trips.size() >= TravelRules.REGULAR_MIN_TRIPS;
    }

    /** 음료 없는 전체 신청 금액. */
    public static PriceBreakdown calculateApplicationPrice(int unitPrice, int travelerCount,
                                                           Iterable<TravelHistory> histories, LocalDate applicationDate) {
        return calculateApplicationPrice(unitPrice, travelerCount, 0, histories, applicationDate);
    }

    /** 실제 여행자 수와 추가 음료를 반영한 전체 신청 금액. 단골이면 10% 할인 (원 미만 버림). */
    public static PriceBreakdown calculateApplicationPrice(int unitPrice, int travelerCount, int drinkPrice,
                                                           Iterable<TravelHistory> histories, LocalDate applicationDate) {
        int subtotal = unitPrice * travelerCount + drinkPrice;
        boolean regular = isRegularCustomer(histories, applicationDate);
        int discount = regular ? (int) ((long) subtotal * TravelRules.REGULAR_DISCOUNT_PERCENT / 100) : 0;
        return new PriceBreakdown(unitPrice, travelerCount, subtotal, regular, discount, subtotal - discount);
    }
}
