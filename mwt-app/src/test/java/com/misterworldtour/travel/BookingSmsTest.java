package com.misterworldtour.travel;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.sms.ConsoleSMSClient;
import com.misterworldtour.sms.SMSNotificationManager;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 출발 확정 문자와 신청 저장의 관계. */
class BookingSmsTest extends BookingTestBase {

    @MockitoSpyBean
    SMSNotificationManager sms;

    @BeforeEach
    void clearSent() {
        ConsoleSMSClient.SENT.clear();
    }

    int logCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sms_log", Integer.class);
    }

    @Test
    void confirmationSmsIsSentAndLoggedPerRecipient() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        LoginSession bob = customer("bob", "밥", "010-2000-0002");
        assertThat(book(bob, p, "밥동반").sms()).isNull();
        ApplicationResult r = book(alice, p);
        assertThat(r.sms().success()).isEqualTo(2);
        assertThat(r.sms().summary()).isEqualTo("출발 확정 문자: 성공 2건");
        assertThat(ConsoleSMSClient.SENT).extracting(s -> s[0]).containsExactlyInAnyOrder("010-2000-0002", "010-1000-0001");
        assertThat(jdbc.queryForList("SELECT status FROM sms_log", String.class)).containsOnly("SUCCESS").hasSize(2);
    }

    @Test
    void sameCustomerTwiceGetsOneMessage() {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(alice, p);
        book(alice, p, "동반");
        assertThat(ConsoleSMSClient.SENT).hasSize(1);
    }

    @Test
    void smsFailureKeepsApplication() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        doThrow(new RuntimeException("boom")).when(sms).sendDepartureConfirmation(any());
        ApplicationResult r = book(alice, p, "배우자");
        assertThat(r.departureStatus()).isEqualTo(DepartureStatus.CONFIRMED);
        assertThat(dataAccess.findApplication(r.applicationId())).isPresent();
        assertThat(r.sms().failed()).isEqualTo(1);
        // 기록은 '발송 중'으로 남아 직원이 재발송할 수 있다
        assertThat(jdbc.queryForObject("SELECT status FROM sms_log", String.class)).isEqualTo("PENDING");
    }

    @Test
    void rolledBackApplicationLeavesNoLog() {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.GRAND);
        ApplicationReviewResult r = review(alice, p, "배우자");
        jdbc.update("UPDATE souvenir SET stock_quantity = 1 WHERE theme = 'HONEYMOON'"); // 차감 단계에서 실패
        assertThatThrownBy(() -> booking.submitTravelApplication(alice.getMemberId(), r)).isInstanceOf(BookingException.class);
        assertThat(logCount()).isZero();
        assertThat(applicationCount()).isZero();
    }
}
