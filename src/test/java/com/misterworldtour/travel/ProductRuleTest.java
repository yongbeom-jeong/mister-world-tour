package com.misterworldtour.travel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 상품 규칙 (Django 판 travel/tests.py ProductRuleTests). */
class ProductRuleTest {

    static TravelProduct product(Theme theme, TourLevel level) {
        return new TravelProduct("테스트 상품", "어딘가", "", theme, level, 1_000_000, 5, LocalDate.of(2026, 12, 1));
    }

    @Test
    void defaultConfigurationFromThemeAndLevel() {
        TravelProduct p = product(Theme.GOLF, TourLevel.PREMIUM);
        assertThat(p.getHotelLevel()).isEqualTo(HotelLevel.STAR5);
        assertThat(p.getMeal()).isEqualTo(MealOption.FINE);
        assertThat(p.getTransport()).isEqualTo(TransportOption.LUXURY_VAN_10);
        assertThat(p.getDepartureStatus()).isEqualTo(DepartureStatus.PENDING);
    }

    @Test
    void honeymoonHasNoProductDepartureStatus() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        assertThat(p.getDepartureStatus()).isNull();
        assertThat(p.getTransport()).isEqualTo(TransportOption.LUXURY_CAR_2);
    }

    @Test
    void honeymoonAndFilialRejectClassic() {
        assertThat(product(Theme.HONEYMOON, TourLevel.CLASSIC).levelError()).isNotNull();
        assertThat(product(Theme.FILIAL, TourLevel.CLASSIC).levelError()).isNotNull();
        assertThat(product(Theme.TREKKING, TourLevel.CLASSIC).levelError()).isNull();
    }

    @Test
    void honeymoonNeedsExactlyTwo() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        assertThat(p.validateApplicationCapacity(0, 1)).isNotNull();
        assertThat(p.validateApplicationCapacity(0, 2)).isNull();
        assertThat(p.validateApplicationCapacity(8, 2)).isNull(); // 다른 신청과 합산하지 않음
    }

    @Test
    void groupCapacityTen() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        assertThat(p.validateApplicationCapacity(7, 3)).isNull();
        assertThat(p.validateApplicationCapacity(8, 3)).contains("남은 자리 2명");
    }

    @Test
    void departureConfirmedAtThreeAndStays() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        assertThat(p.updateDepartureStatus(2)).isEqualTo(DepartureStatus.PENDING);
        assertThat(p.updateDepartureStatus(3)).isEqualTo(DepartureStatus.CONFIRMED);
        assertThat(p.updateDepartureStatus(1)).isEqualTo(DepartureStatus.CONFIRMED);
    }

    @Test
    void tripEndDateCountsDepartureAsDayOne() {
        assertThat(product(Theme.GOLF, TourLevel.GRAND).getTripEndDate()).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    void threeYearWindowHandlesLeapDay() {
        TravelHistory h = new TravelHistory(1L, "t", "p", TourLevel.GRAND, 3, 1, true, LocalDate.of(2025, 2, 28));
        assertThat(h.isCompletedWithinThreeYears(LocalDate.of(2028, 2, 29))).isTrue();
        assertThat(h.isCompletedWithinThreeYears(LocalDate.of(2028, 3, 1))).isFalse();
        TravelHistory future = new TravelHistory(1L, "f", "p", TourLevel.GRAND, 3, 1, true, LocalDate.of(2030, 1, 1));
        assertThat(future.isCompletedWithinThreeYears(LocalDate.of(2026, 1, 1))).isFalse();
    }
}
