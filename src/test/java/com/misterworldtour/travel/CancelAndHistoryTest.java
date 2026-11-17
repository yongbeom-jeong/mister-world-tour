package com.misterworldtour.travel;

import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.travel.BookingService.ApplicationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 신청 취소(2026-11-12 결정)와 완료 여행 이력 (Django 판 CancelTests·TripCompletionTests·TripCountTests·HistoryViewTests). */
class CancelAndHistoryTest extends BookingTestBase {

    @Autowired
    TravelService travel;

    /** 신청 뒤 출발일을 과거로 옮긴다 (여행 기간 4일). */
    void travel(TravelProduct p, int daysAgo) {
        jdbc.update("UPDATE travel_product SET departure_date = ? WHERE id = ?", LocalDate.now().minusDays(daysAgo), p.getId());
    }

    List<String> historyNames(MemberPrincipal m) {
        return jdbc.queryForList("SELECT product_name FROM travel_history WHERE member_id = ? ORDER BY id", String.class,
                m.getId());
    }

    // ---------------------------------------------------------------- 취소

    @Test
    void cancelBeforeConfirmationFreesSeats() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationResult r = book(alice, p, "동반");
        assertThat(booking.currentTravelerCount(p.getId())).isEqualTo(2);
        booking.cancelApplication(alice.getId(), r.applicationId());
        assertThat(booking.currentTravelerCount(p.getId())).isZero();
        assertThat(booking.activeApplicationCount(alice.getId())).isZero();
    }

    @Test
    void cancelledApplicantNotCountedOrNotifiedAtConfirmation() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        ApplicationResult first = book(alice, p, "동반");
        booking.cancelApplication(alice.getId(), first.applicationId());
        MemberPrincipal bob = customer("bob");
        ApplicationResult r = book(bob, p, "x", "y"); // 3명 → 확정, 취소한 앨리스는 빠짐
        assertThat(r.newlyConfirmed()).isTrue();
        assertThat(r.recipientMemberIds()).containsExactly(bob.getId());
        assertThat(r.souvenirsIssued()).isEqualTo(3);
    }

    @Test
    void confirmedCannotBeCancelled() {
        ApplicationResult r = book(alice, product(Theme.HONEYMOON, TourLevel.GRAND), "배우자");
        assertThatThrownBy(() -> booking.cancelApplication(alice.getId(), r.applicationId()))
                .hasMessageContaining("출발이 확정된");
    }

    @Test
    void cannotCancelTwiceOrOthers() {
        ApplicationResult r = book(alice, product(Theme.GOLF, TourLevel.GRAND));
        long bob = customer("bob").getId();
        assertThatThrownBy(() -> booking.cancelApplication(bob, r.applicationId())).hasMessageContaining("찾을 수 없");
        booking.cancelApplication(alice.getId(), r.applicationId());
        assertThatThrownBy(() -> booking.cancelApplication(alice.getId(), r.applicationId())).hasMessageContaining("이미 취소");
    }

    @Test
    void cancelHttpFlow() throws Exception {
        ApplicationResult r = book(alice, product(Theme.GOLF, TourLevel.GRAND));
        String url = "/travel/applications/" + r.applicationId() + "/";
        mvc.perform(get(url).with(user(alice))).andExpect(content().string(containsString("신청 취소")));
        mvc.perform(post(url + "cancel/").with(user(alice)).with(csrf())).andExpect(redirectedUrl(url));
        mvc.perform(get(url).with(user(alice)))
                .andExpect(content().string(containsString("취소한 신청")))
                .andExpect(content().string(containsString("이미 취소한 신청입니다")));
        mvc.perform(post(url + "cancel/").with(user(customer("bob"))).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void applyReviewShowsCancelRule() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/products/{id}/apply/", p.getId()).session(session).with(user(alice)).with(csrf())
                .param("config", "{}"));
        mvc.perform(post("/travel/apply/").session(session).with(user(alice)).with(csrf())
                        .param("action", "review").param("traveler_name", "x").param("traveler_contact", ""))
                .andExpect(content().string(containsString(BookingService.CANCEL_RULE)));
    }

    // ---------------------------------------------------------------- 완료 이력

    @Test
    void confirmedTripBecomesHistoryAfterLastDay() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.PREMIUM);
        ApplicationResult r = book(alice, p, "배우자");
        travel(p, 3); // 4일 여행의 마지막 날이 오늘 → 아직 여행 중
        assertThat(travel.completeFinishedTrips(alice.getId())).isZero();
        travel(p, 4); // 어제 끝남
        assertThat(travel.completeFinishedTrips(alice.getId())).isEqualTo(1);
        TravelHistory h = travel.viewPreviousTravelHistory(alice.getId()).get(0);
        assertThat(h.isCompleted()).isTrue();
        assertThat(h.getCompletedAt()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(h.getProductName()).isEqualTo(p.getName());
        assertThat(h.getPrice()).isEqualTo(applications.findById(r.applicationId()).orElseThrow().getFinalAmount());
        assertThat(travel.completeFinishedTrips(alice.getId())).isZero(); // 중복 없음
    }

    @Test
    void unconfirmedOrCancelledTripIsNotHistory() {
        TravelProduct pending = product(Theme.GOLF, TourLevel.GRAND, 1_000_000, 30, "미확정");
        book(alice, pending); // 1명 → 미확정
        TravelProduct cancelled = product(Theme.GOLF, TourLevel.GRAND, 1_000_000, 30, "취소");
        ApplicationResult r = book(alice, cancelled);
        booking.cancelApplication(alice.getId(), r.applicationId());
        travel(pending, 30);
        travel(cancelled, 30);
        assertThat(travel.completeFinishedTrips(null)).isZero();
    }

    @Test
    void everyApplicantOfGroupGetsHistoryAndSameTripCountsOnce() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        MemberPrincipal bob = customer("bob", "밥", "010-2000-0002");
        int a1 = applications.findById(book(alice, p).applicationId()).orElseThrow().getFinalAmount();
        int a2 = applications.findById(book(alice, p).applicationId()).orElseThrow().getFinalAmount();
        book(bob, p); // 3명 → 확정
        travel(p, 10);
        assertThat(travel.completeFinishedTrips(null)).isEqualTo(2);
        assertThat(historyNames(alice)).hasSize(1); // 같은 상품 신청 2건이어도 다녀온 여행은 1회
        assertThat(historyNames(bob)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT price FROM travel_history WHERE member_id = ?", Integer.class,
                alice.getId())).isEqualTo(a1 + a2);
    }

    @Test
    void historyPopupAndDiscountReflectFinishedTrips() throws Exception {
        for (int i = 0; i < 3; i++) { // 다녀온 허니문 3번 → 단골
            TravelProduct p = product(Theme.HONEYMOON, TourLevel.PREMIUM, 1_000_000, 30, "다녀온 여행 " + i);
            book(alice, p, "배우자");
            travel(p, 20);
        }
        mvc.perform(get("/travel/history/").with(user(alice)))
                .andExpect(content().string(containsString("다녀온 여행 0")))
                .andExpect(content().string(containsString("완료")));
        Review r = review(alice, product(Theme.GOLF, TourLevel.GRAND));
        assertThat(r.breakdown().regular()).isTrue();
    }

    @Test
    void historyRecentFirstOnlyMineAndEmptyMessage() throws Exception {
        mvc.perform(get("/travel/history/").with(user(alice)))
                .andExpect(content().string(containsString("이전 여행 이력이 없습니다")));
        travel.seedHistory(alice.getId(), "a", "옛날여행", TourLevel.GRAND, 3, 1_234_000, LocalDate.of(2024, 1, 1));
        travel.seedHistory(alice.getId(), "b", "최근여행", TourLevel.GRAND, 3, 1, LocalDate.of(2026, 5, 1));
        travel.seedHistory(alice.getId(), "c", "미완료여행", TourLevel.GRAND, 3, 1, null);
        MemberPrincipal other = customer("other");
        travel.seedHistory(other.getId(), "d", "남의여행", TourLevel.GRAND, 3, 1, LocalDate.of(2026, 6, 1));
        assertThat(travel.viewPreviousTravelHistory(alice.getId())).extracting(TravelHistory::getProductName)
                .containsExactly("최근여행", "옛날여행", "미완료여행");
        mvc.perform(get("/travel/history/").with(user(alice)))
                .andExpect(content().string(containsString("1,234,000원")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("남의여행"))));
    }

    @Test
    void historyIsCustomerOnlyAndPopupIsInLayout() throws Exception {
        mvc.perform(get("/travel/history/")).andExpect(redirectedUrl("/members/login/"));
        mvc.perform(get("/travel/history/").with(user(principals.create("emp", com.misterworldtour.members.Role.STAFF))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/").with(user(alice))).andExpect(content().string(containsString("historyModal")));
    }
}
