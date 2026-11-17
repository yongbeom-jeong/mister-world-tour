package com.misterworldtour.sms;

import com.misterworldtour.TestDb;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.LoginSessionSupport;
import com.misterworldtour.members.Role;
import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSNotificationAPI.DepartureNotice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 발송 기록과 직원 재발송 (SMSLogTests). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SMSLogWebTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SMSNotificationManager sms;
    @Autowired
    LoginSessionSupport principals;

    LoginSession staff;
    final DepartureNotice notice = new DepartureNotice(7L, "다낭 골프", LocalDate.of(2026, 12, 1));
    final Recipient bob = new Recipient(null, "밥", "010-2000-0002");

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        staff = principals.create("emp", Role.STAFF);
    }

    long logId() {
        return jdbc.queryForObject("SELECT MAX(id) FROM sms_log", Long.class);
    }

    String logStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM sms_log WHERE id = ?", String.class, id);
    }

    void failAll() {
        sms.sendDepartureConfirmation(List.of(bob), notice, (c, m) -> {
            throw new SMSServiceClient.SendException("실패");
        }, System::nanoTime);
    }

    @Test
    void eachRecipientLoggedWithResult() {
        sms.sendDepartureConfirmation(List.of(bob, new Recipient(null, "캐럴", "010-3")), notice,
                new ConsoleSMSClient(), System::nanoTime);
        assertThat(jdbc.queryForList("SELECT recipient_name || ':' || status FROM sms_log ORDER BY id", String.class))
                .containsExactly("밥:SUCCESS", "캐럴:SUCCESS");
        assertThat(jdbc.queryForObject("SELECT product_name FROM sms_log WHERE id = ?", String.class, logId()))
                .isEqualTo("다낭 골프");
    }

    @Test
    void resendFailed() {
        failAll();
        long id = logId();
        SMSMessage after = sms.resendMessage(id, staff.getMemberId(), false, new ConsoleSMSClient(), System::nanoTime);
        assertThat(after.getStatus()).isEqualTo(SMSMessage.Status.SUCCESS);
        assertThat(after.getAttemptCount()).isEqualTo(5); // 자동 4회 + 재발송 1회 누적
        assertThat(after.getResendCount()).isEqualTo(1);
        assertThat(after.getResentById()).isEqualTo(staff.getMemberId());
        assertThatThrownBy(() -> sms.resendMessage(id, staff.getMemberId(), false)).hasMessageContaining("성공");
    }

    @Test
    void unknownNeedsDuplicateConfirmation() {
        sms.sendDepartureConfirmation(List.of(bob), notice, (c, m) -> {
            throw new SMSServiceClient.UnknownResultException("시간 초과");
        }, System::nanoTime);
        long id = logId();
        assertThatThrownBy(() -> sms.resendMessage(id, staff.getMemberId(), false)).hasMessageContaining("이미 받았을 수");
        assertThat(sms.resendMessage(id, staff.getMemberId(), true).getStatus()).isEqualTo(SMSMessage.Status.SUCCESS);
    }

    @Test
    void stalePendingIsResendable() {
        sms.prepareDepartureConfirmation(List.of(bob), notice); // 기록만 만들고 보내지 못함
        long id = logId();
        assertThatThrownBy(() -> sms.resendMessage(id, staff.getMemberId(), true)).hasMessageContaining("발송 중");
        jdbc.update("UPDATE sms_log SET updated_at = ? WHERE id = ?", OffsetDateTime.now().minusMinutes(6), id);
        assertThat(sms.problemCount()).isEqualTo(1);
        assertThat(sms.resendMessage(id, staff.getMemberId(), true).getStatus()).isEqualTo(SMSMessage.Status.SUCCESS);
    }

    @Test
    void staffPageAndResend() throws Exception {
        failAll();
        long id = logId();
        mvc.perform(get("/sms/staff/logs/").param("filter", "problems").with(login(staff)))
                .andExpect(content().string(containsString("다시 보내기")))
                .andExpect(content().string(containsString("다낭 골프")));
        mvc.perform(post("/sms/staff/logs/{id}/resend/", id).with(login(staff)).with(csrf())
                        .param("next", "/sms/staff/logs/?filter=problems"))
                .andExpect(redirectedUrl("/sms/staff/logs/?filter=problems"));
        assertThat(logStatus(id)).isEqualTo("SUCCESS");
        mvc.perform(post("/sms/staff/logs/{id}/resend/", id).with(login(staff)).with(csrf())
                        .param("next", "https://evil.example/"))
                .andExpect(redirectedUrl("/sms/staff/logs/"));
        mvc.perform(get("/sms/staff/logs/").with(login(staff)))
                .andExpect(content().string(containsString("마지막 재발송 이름emp")));
    }

    @Test
    void customerCannotSeeLogs() throws Exception {
        LoginSession customer = principals.create("cust", Role.CUSTOMER);
        mvc.perform(get("/sms/staff/logs/").with(login(customer))).andExpect(status().isForbidden());
    }
}
