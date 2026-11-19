package com.misterworldtour.travel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 가격·단골 할인. */
class PricingTest {

    static TravelProduct product(TourLevel level, int price) {
        return new TravelProduct("p", "d", "", Theme.GOLF, level, price, 5, LocalDate.of(2026, 12, 1));
    }

    static TravelConfiguration config(TravelProduct p, String hotel, String meal, String... drinks) {
        return new TravelConfiguration(hotel, p.getTransport().name(), meal, List.of(drinks));
    }

    static TravelHistory history(String id, LocalDate completedAt, boolean completed) {
        return new TravelHistory(1L, id, "p", TourLevel.GRAND, 3, 1, completed, completedAt);
    }

    @Test
    void unitPriceWithOptions() {
        TravelProduct p = product(TourLevel.CLASSIC, 1_000_000);
        assertThat(TravelPriceCalculator.calculateUnitPrice(p, TravelConfiguration.defaultOf(p))).isEqualTo(1_000_000);
        assertThat(TravelPriceCalculator.calculateUnitPrice(p, config(p, "STAR5", "FINE", "CHAMPAGNE", "WINE")))
                .isEqualTo(1_000_000 + 350_000 + 200_000 + 50_000);
        TravelProduct premium = product(TourLevel.PREMIUM, 1_000_000);
        assertThat(TravelPriceCalculator.calculateUnitPrice(premium, config(premium, "STAR4", "LOCAL")))
                .isEqualTo(1_000_000 - 200_000 - 120_000);
        TravelProduct grand = product(TourLevel.GRAND, 1_000_000);
        assertThat(TravelPriceCalculator.calculateUnitPrice(grand, config(grand, "STAR5", "LUNCHBOX")))
                .isEqualTo(1_000_000 + 200_000 - 80_000);
    }

    @Test
    void unitPriceNeverBelowHalfOfBase() {
        TravelProduct cheap = product(TourLevel.PREMIUM, 300_000); // 하한 15만
        assertThat(TravelPriceCalculator.calculateUnitPrice(cheap, config(cheap, "STAR3", "LUNCHBOX"))).isEqualTo(150_000);
    }

    @Test
    void regularCustomerGetsTenPercent() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        List<TravelHistory> hs = List.of(history("t0", LocalDate.of(2025, 1, 1), true),
                history("t1", LocalDate.of(2025, 1, 2), true), history("t2", LocalDate.of(2025, 1, 3), true));
        TravelPriceCalculator.PriceBreakdown b = TravelPriceCalculator.calculateApplicationPrice(1_000_000, 2, hs, today);
        assertThat(b.regular()).isTrue();
        assertThat(List.of(b.subtotal(), b.discountAmount(), b.finalAmount()))
                .containsExactly(2_000_000, 200_000, 1_800_000);
    }

    @Test
    void notRegularCases() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        LocalDate d = LocalDate.of(2025, 1, 1);
        List<List<TravelHistory>> cases = List.of(
                List.of(history("t0", d, true), history("t1", d, true)),                       // 2회뿐
                List.of(history("same", d, true), history("same", d, true), history("same", d, true)), // 같은 여행 중복
                List.of(history("a", LocalDate.of(2023, 1, 1), true), history("b", LocalDate.of(2023, 1, 1), true),
                        history("c", LocalDate.of(2023, 1, 1), true)),                           // 3년 지남
                List.of(history("a", d, false), history("b", d, false), history("c", d, false)), // 미완료
                List.of(history("a", LocalDate.of(2027, 1, 1), true), history("b", LocalDate.of(2027, 1, 1), true),
                        history("c", LocalDate.of(2027, 1, 1), true)));                          // 미래 완료일
        for (List<TravelHistory> hs : cases) {
            assertThat(TravelPriceCalculator.calculateApplicationPrice(100, 1, hs, today).discountAmount()).isZero();
        }
    }

    @Test
    void discountDoesNotOverflowForLargeAmounts() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        LocalDate d = LocalDate.of(2025, 1, 1);
        List<TravelHistory> hs = List.of(history("a", d, true), history("b", d, true), history("c", d, true));
        TravelPriceCalculator.PriceBreakdown b = TravelPriceCalculator.calculateApplicationPrice(50_000_000, 10, hs, today);
        assertThat(b.discountAmount()).isEqualTo(50_000_000);
    }
}
