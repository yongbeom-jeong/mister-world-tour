package com.misterworldtour.travel;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;
import com.misterworldtour.travel.TravelManager.ConfigurationException;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UC4 구성, UC5 검토·등록·출발 확정·기념품 차감. */
class BookingServiceTest extends BookingTestBase {

    // ---------------------------------------------------------------- 구성

    @Test
    void changeOneOptionUpdatesPrice() {
        TravelProduct p = product(Theme.GOLF, TourLevel.CLASSIC); // 3등급 호텔·도시락
        var r = booking.configureTravelProduct(p, TravelConfiguration.defaultOf(p), Map.of("hotel_level", "STAR5"));
        assertThat(r.configuration().hotelLevel()).isEqualTo("STAR5");
        assertThat(r.configuration().meal()).isEqualTo("LUNCHBOX"); // 바꾸지 않은 항목은 유지
        assertThat(r.unitPrice()).isEqualTo(1_350_000);
    }

    @Test
    void invalidChangeKeepsPrevious() {
        TravelProduct p = product(Theme.GOLF, TourLevel.CLASSIC);
        TravelConfiguration current = TravelConfiguration.defaultOf(p);
        assertThatThrownBy(() -> booking.configureTravelProduct(p, current, Map.of("drinks", List.of("WINE"))))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getErrors()).containsKey("drinks"));
        assertThat(current).isEqualTo(TravelConfiguration.defaultOf(p)); // 직전 구성은 그대로
    }

    @Test
    void transportRulesByTheme() {
        TravelProduct honeymoon = product(Theme.HONEYMOON, TourLevel.GRAND);
        TravelConfiguration current = TravelConfiguration.defaultOf(honeymoon);
        assertThatThrownBy(() -> booking.configureTravelProduct(honeymoon, current, Map.of("transport", "LUXURY_VAN_10")))
                .isInstanceOf(ConfigurationException.class);
        assertThat(booking.configureTravelProduct(honeymoon, current, Map.of("transport", "LIMOUSINE")).configuration().transport())
                .isEqualTo("LIMOUSINE");
    }

    @Test
    void includedChampagneNotCharged() {
        TravelProduct p = product(Theme.GOLF, TourLevel.PREMIUM); // 고급 식사(샴페인 포함)
        assertThat(booking.configureTravelProduct(p, TravelConfiguration.defaultOf(p),
                Map.of("drinks", List.of("CHAMPAGNE"))).unitPrice()).isEqualTo(1_000_000);
    }

    @Test
    void restrictedThemesCannotChooseBelowGrand() {
        TravelProduct p = product(Theme.FILIAL, TourLevel.GRAND);
        TravelConfiguration current = TravelConfiguration.defaultOf(p);
        assertThatThrownBy(() -> booking.configureTravelProduct(p, current, Map.of("hotel_level", "STAR3")))
                .isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> booking.configureTravelProduct(p, current, Map.of("meal", "LUNCHBOX")))
                .isInstanceOf(ConfigurationException.class);
        TravelProduct golf = product(Theme.GOLF, TourLevel.GRAND);
        assertThat(booking.configureTravelProduct(golf, TravelConfiguration.defaultOf(golf),
                Map.of("hotel_level", "STAR3")).configuration().hotelLevel()).isEqualTo("STAR3");
    }

    // ---------------------------------------------------------------- 검토

    @Test
    void reviewSavesNothing() {
        review(alice, product(Theme.GOLF, TourLevel.GRAND), "밥");
        assertThat(applicationCount()).isZero();
    }

    @Test
    void honeymoonRequiresExactlyTwo() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        assertThatThrownBy(() -> review(alice, p)).isInstanceOf(BookingException.class);
        assertThatThrownBy(() -> review(alice, p, "a", "b")).isInstanceOf(BookingException.class);
        assertThat(review(alice, p, "배우자").travelers()).hasSize(2);
    }

    @Test
    void capacityCountsExistingTravelers() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(customer("bob"), p, "1", "2", "3", "4", "5", "6", "7"); // 8명
        assertThatThrownBy(() -> review(alice, p, "x", "y")) // 8 + 3 > 10
                .isInstanceOf(BookingException.class).hasMessageContaining("정원");
    }

    @Test
    void souvenirStockShortage() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        jdbc.update("UPDATE souvenir SET stock_quantity = 1 WHERE theme = 'GOLF'");
        assertThatThrownBy(() -> review(alice, p, "밥")).isInstanceOf(BookingException.class).hasMessageContaining("재고");
    }

    @Test
    void regularCustomerDiscount() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO travel_history (member_id, completed_travel_id, product_name, tour_level, "
                    + "travel_days, price, completed, completed_at) VALUES (?, ?, 'p', 'GRAND', 3, 1, TRUE, ?)",
                    alice.getMemberId(), "t" + i, today.minusDays(100L * (i + 1)));
        }
        ApplicationReviewResult r = review(alice, product(Theme.GOLF, TourLevel.GRAND), "밥");
        assertThat(List.of(r.price().subtotal(), r.price().discountAmount(), r.price().finalAmount()))
                .containsExactly(2_000_000, 200_000, 1_800_000);
    }

    @Test
    void invalidCompanionContact() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        List<TravelerInput> rows = List.of(new TravelerInput("x", "", true), new TravelerInput("밥", "123", false));
        assertThatThrownBy(() -> booking.reviewApplication(alice.getMemberId(), p.getProductId(), TravelConfiguration.defaultOf(p), rows))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.getErrors()).containsKey("traveler_1"));
    }

    @Test
    void firstRowIsAccountInfoAndContactsAreNormalized() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        List<TravelerInput> rows = List.of(new TravelerInput("무시됨", "", true),
                new TravelerInput("동반", "01099990000", false));
        ApplicationReviewResult r = booking.reviewApplication(alice.getMemberId(), p.getProductId(), TravelConfiguration.defaultOf(p), rows);
        assertThat(r.travelers().get(0)).isEqualTo(new TravelerInput("앨리스", "010-1000-0001", true));
        assertThat(r.travelers().get(1).contact()).isEqualTo("010-9999-0000");
    }

    // ---------------------------------------------------------------- 등록·출발 확정

    @Test
    void groupConfirmsAtThreeAndListsAllApplicants() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        LoginSession bob = customer("bob");
        ApplicationResult first = book(bob, p, "밥동반"); // 2명
        assertThat(first.departureStatus()).isEqualTo(DepartureStatus.PENDING);
        assertThat(first.recipientMemberIds()).isEmpty();

        ApplicationResult second = book(alice, p); // +1 → 3명
        assertThat(second.newlyConfirmed()).isTrue();
        assertThat(second.departureStatus()).isEqualTo(DepartureStatus.CONFIRMED);
        assertThat(second.recipientMemberIds()).containsExactlyInAnyOrder(bob.getMemberId(), alice.getMemberId());

        LoginSession carol = customer("carol");
        ApplicationResult third = book(carol, p); // 이미 확정된 상품의 추가 신청
        assertThat(third.newlyConfirmed()).isFalse();
        assertThat(third.recipientMemberIds()).containsExactly(carol.getMemberId());
    }

    @Test
    void sameCustomerTwiceIsOneRecipient() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(alice, p);
        assertThat(book(alice, p, "동반").recipientMemberIds()).containsExactly(alice.getMemberId());
    }

    @Test
    void honeymoonEachApplicationConfirmedIndependently() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        LoginSession bob = customer("bob");
        ApplicationResult r1 = book(alice, p, "배우자");
        ApplicationResult r2 = book(bob, p, "배우자2");
        assertThat(r1.departureStatus()).isEqualTo(DepartureStatus.CONFIRMED);
        assertThat(r1.recipientMemberIds()).containsExactly(alice.getMemberId());
        assertThat(r2.recipientMemberIds()).containsExactly(bob.getMemberId());
        assertThat(dataAccess.findProduct(p.getProductId()).orElseThrow().getDepartureStatus()).isNull();
    }

    @Test
    void resubmitSameKeyDoesNotDuplicate() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p);
        booking.submitTravelApplication(alice.getMemberId(), r);
        assertThat(booking.submitTravelApplication(alice.getMemberId(), r).duplicate()).isTrue();
        assertThat(applicationCount()).isEqualTo(1);
        assertThat(booking.currentTravelerCount(p.getProductId())).isEqualTo(1);
    }

    @Test
    void resubmitSameKeyWithDifferentContentIsRejected() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p, "동반");
        booking.submitTravelApplication(alice.getMemberId(), r);
        ApplicationReviewResult changedTravelers = new ApplicationReviewResult(r.requestKey(), r.productId(), r.memberId(), r.configuration(),
                List.of(r.travelers().get(0), new TravelerInput("다른 사람", "", false)), r.price());
        TravelConfiguration c = r.configuration();
        ApplicationReviewResult changedConfig = new ApplicationReviewResult(r.requestKey(), r.productId(), r.memberId(),
                new TravelConfiguration("STAR3".equals(c.hotelLevel()) ? "STAR4" : "STAR3", c.transport(), c.meal(), c.drinks()),
                r.travelers(), r.price());
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), changedTravelers))
                .isInstanceOf(BookingException.class);
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), changedConfig))
                .isInstanceOf(BookingException.class);
        assertThat(applicationCount()).isEqualTo(1);
    }

    @Test
    void priceChangeAfterReviewIsNotSaved() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p);
        jdbc.update("UPDATE travel_product SET base_price = 1200000 WHERE id = ?", p.getProductId());
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), r))
                .isInstanceOf(TravelManager.PriceChangedException.class);
        assertThat(applicationCount()).isZero();
    }

    @Test
    void capacityRecheckedAtSubmit() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p, "a", "b"); // 3명, 검토 시점엔 가능
        book(customer("bob"), p, "1", "2", "3", "4", "5", "6", "7"); // 그 사이 8명 신청
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), r)).isInstanceOf(BookingException.class);
        assertThat(booking.currentTravelerCount(p.getProductId())).isEqualTo(8);
    }

    @Test
    void otherCustomerCannotSubmitReview() {
        ApplicationReviewResult r = review(alice, product(Theme.GOLF, TourLevel.GRAND));
        long mallory = customer("mallory").getMemberId();
        assertThatThrownBy(() -> booking.submitTravelApplication(mallory, r)).isInstanceOf(BookingException.class);
    }

    // ---------------------------------------------------------------- 기념품 차감 (테마 품목마다 여행자 1인당 1개)

    @Test
    void groupDeductsAllTravelersWhenConfirmed() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(customer("bob"), p, "동반"); // 2명, 확정 전 → 차감 없음
        assertThat(stock("골프공")).isEqualTo(100);
        assertThat(book(alice, p).souvenirsIssued()).isEqualTo(3); // 3명 → 확정, 3개 차감
        assertThat(stock("골프공")).isEqualTo(97);
        book(customer("carol"), p, "x"); // 확정 뒤 추가 2명 → 그 신청분만
        assertThat(stock("골프공")).isEqualTo(95);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_issue", Integer.class)).isEqualTo(3);
    }

    @Test
    void honeymoonDeductsPerApplication() {
        book(alice, product(Theme.HONEYMOON, TourLevel.GRAND), "배우자");
        assertThat(stock("커플 기념티")).isEqualTo(98);
    }

    @Test
    void everySouvenirOfThemeIsDeducted() {
        jdbc.update("INSERT INTO souvenir (name, name_key, theme, stock_quantity) VALUES ('골프 장갑', '골프 장갑', 'GOLF', 10)");
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(alice, p, "a", "b"); // 3명 → 확정
        assertThat(List.of(stock("골프공"), stock("골프 장갑"))).containsExactly(97, 7);
        assertThat(booking.themeSouvenirNames(p)).containsExactly("골프공", "골프 장갑");
    }

    @Test
    void otherThemeNotDeducted() {
        book(alice, product(Theme.GOLF, TourLevel.GRAND), "a", "b");
        assertThat(stock("기념품 스카프")).isEqualTo(100);
    }

    @Test
    void confirmationNeedsStockForAllPendingTravelers() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(customer("bob"), p, "동반"); // 2명 대기
        jdbc.update("UPDATE souvenir SET stock_quantity = 2 WHERE name = '골프공'");
        assertThatThrownBy(() -> book(alice, p)).hasMessageContaining("3개가 필요"); // 확정되면 3개 필요
        assertThat(applicationCount()).isEqualTo(1); // 신청도 저장되지 않음
        assertThat(stock("골프공")).isEqualTo(2);
    }

    @Test
    void shortageAtSubmitRollsBackEverything() {
        jdbc.update("INSERT INTO souvenir (name, name_key, theme, stock_quantity) VALUES ('골프 장갑', '골프 장갑', 'GOLF', 10)");
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p, "a", "b");
        jdbc.update("UPDATE souvenir SET stock_quantity = 1 WHERE name = '골프 장갑'"); // 검토 뒤 재고가 줄어듦
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), r)).isInstanceOf(BookingException.class);
        assertThat(applicationCount()).isZero();
        assertThat(stock("골프공")).isEqualTo(100); // 앞 품목 차감도 되돌아감
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_issue", Integer.class)).isZero();
    }

    @Test
    void themeWithoutSouvenirCannotApply() {
        TravelProduct p = product(Theme.TREKKING, TourLevel.GRAND);
        jdbc.update("UPDATE souvenir SET is_active = FALSE WHERE theme = 'TREKKING'");
        assertThatThrownBy(() -> review(alice, p)).hasMessageContaining("기념품 정보가 없어");
    }
}
