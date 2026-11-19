package com.misterworldtour.sms;

import com.misterworldtour.TestDb;
import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSNotificationAPI.DepartureNotice;
import com.misterworldtour.sms.SMSNotificationAPI.SMSBatchResult;
import com.misterworldtour.sms.SMSServiceClient.SendException;
import com.misterworldtour.sms.SMSServiceClient.UnknownResultException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 출발 확정 문자: 문구·재시도·결과 불명·정보 누락·시간 한도. */
@SpringBootTest(properties = "mwt.sms.batch-time-limit=40")
@ActiveProfiles("test")
class SMSDeliveryTest {

    @Autowired
    SMSNotificationManager sms;
    @Autowired
    JdbcTemplate jdbc;

    final DepartureNotice notice = new DepartureNotice(1L, "다낭 골프 챌린지", LocalDate.of(2026, 12, 1));
    final Recipient alice = new Recipient(null, "앨리스", "010-1000-0001");

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        ConsoleSMSClient.SENT.clear();
    }

    /** 처음 failures 번 실패하고 그다음 성공. */
    static class FlakyClient implements SMSServiceClient {
        final int failures;
        final AtomicInteger calls = new AtomicInteger();

        FlakyClient(int failures) {
            this.failures = failures;
        }

        @Override
        public String sendSMS(String contact, String message) {
            if (calls.incrementAndGet() <= failures) {
                throw new SendException("실패 " + calls.get());
            }
            return "group-1";
        }
    }

    static class UnknownClient implements SMSServiceClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String sendSMS(String contact, String message) {
            calls.incrementAndGet();
            throw new UnknownResultException("시간 초과");
        }
    }

    /** 호출마다 시계를 step 초 진행시키는 느린 클라이언트. */
    static class SlowClient implements SMSServiceClient {
        final AtomicLong clock;
        final long stepNanos;
        final Duration timeout;
        final boolean fail;
        final List<String> sent = new ArrayList<>();

        SlowClient(AtomicLong clock, int stepSeconds, int timeoutSeconds, boolean fail) {
            this.clock = clock;
            this.stepNanos = Duration.ofSeconds(stepSeconds).toNanos();
            this.timeout = Duration.ofSeconds(timeoutSeconds);
            this.fail = fail;
        }

        @Override
        public String sendSMS(String contact, String message) {
            clock.addAndGet(stepNanos);
            if (fail) {
                throw new SendException("느린 실패");
            }
            sent.add(contact);
            return "";
        }

        @Override
        public Duration timeout() {
            return timeout;
        }
    }

    SMSBatchResult send(List<Recipient> recipients, SMSServiceClient client) {
        return sms.sendDepartureConfirmation(recipients, notice, client, System::nanoTime);
    }

    String logStatus() {
        return jdbc.queryForObject("SELECT status FROM sms_log ORDER BY id DESC LIMIT 1", String.class);
    }

    @Test
    void messageContainsProductAndDate() {
        assertThat(notice.message(alice)).isEqualTo("[미스터월드투어] 앨리스님, 다낭 골프 챌린지 12월 1일 출발 확정되었습니다.");
    }

    @Test
    void longNamesStillFitInShortMessage() {
        DepartureNotice longOne = new DepartureNotice(1L, "아주 아주 긴 이름을 가진 스위스 융프라우 알프스 프리미엄 트레킹 특별 상품",
                LocalDate.of(2026, 12, 1));
        String text = longOne.message(new Recipient(1L, "아주긴이름을가진고객님", "010"));
        assertThat(SMSNotificationAPI.smsBytes(text)).isLessThanOrEqualTo(SMSNotificationManager.SMS_MAX_BYTES);
        assertThat(text).startsWith("[미스터월드투어] ").endsWith("12월 1일 출발 확정").contains("..");
    }

    @Test
    void retriesUntilSuccess() {
        FlakyClient client = new FlakyClient(2);
        SMSBatchResult r = send(List.of(alice), client);
        assertThat(r.success()).isEqualTo(1);
        assertThat(client.calls.get()).isEqualTo(3);
        assertThat(r.messages().get(0).getAttemptCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM sms_log", String.class)).isEqualTo("group-1");
    }

    @Test
    void givesUpAfterFourAttempts() {
        FlakyClient client = new FlakyClient(10);
        SMSBatchResult r = send(List.of(alice), client);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(client.calls.get()).isEqualTo(SMSMessage.MAX_ATTEMPTS);
        assertThat(logStatus()).isEqualTo("FAILURE");
        assertThat(r.summary()).isEqualTo("출발 확정 문자: 성공 0건, 실패 1건");
    }

    @Test
    void unknownResultIsNotRetried() {
        UnknownClient client = new UnknownClient();
        SMSBatchResult r = send(List.of(alice), client);
        assertThat(r.unknown()).isEqualTo(1);
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(logStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void missingContactSkippedOthersSent() {
        SMSBatchResult r = send(List.of(new Recipient(2L, "연락처없음", ""), alice), new ConsoleSMSClient());
        assertThat(r.success()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(ConsoleSMSClient.SENT).hasSize(1);
        // 정보가 없는 대상은 발송 기록을 만들지 않는다 (설계 상태도 11.1)
        assertThat(jdbc.queryForList("SELECT status FROM sms_log ORDER BY id", String.class))
                .containsExactly("SUCCESS");
    }

    @Test
    void rejectedNumberIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        SMSServiceClient client = (contact, message) -> {
            calls.incrementAndGet();
            throw new SMSServiceClient.RejectedException("허용 목록 밖");
        };
        SMSBatchResult r = send(List.of(alice), client);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(r.messages().get(0).getStatus()).isEqualTo(SMSMessage.Status.FAILURE);
    }

    @Test
    void batchStopsBeforeExceedingTimeLimit() {
        AtomicLong clock = new AtomicLong();
        SlowClient client = new SlowClient(clock, 15, 10, false); // 1통 15초, 1회 최대 10초 대기
        List<Recipient> many = List.of(alice, new Recipient(2L, "밥", "010-2"), new Recipient(3L, "캐럴", "010-3"),
                new Recipient(4L, "데이브", "010-4"));
        SMSBatchResult r = sms.sendDepartureConfirmation(many, notice, client, clock::get);
        // 0·15·30초에 시작: 30+10 = 40 까지 가능, 45초에 시작하면 한도 초과라 보내지 않음
        assertThat(client.sent).hasSize(3);
        assertThat(r.success()).isEqualTo(3);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.messages().get(3).getLastError()).contains("시간 한도");
    }

    @Test
    void retriesAlsoRespectLimit() {
        AtomicLong clock = new AtomicLong();
        SlowClient client = new SlowClient(clock, 15, 10, true);
        SMSBatchResult r = sms.sendDepartureConfirmation(List.of(alice), notice, client, clock::get);
        assertThat(r.messages().get(0).getAttemptCount()).isEqualTo(3);
        assertThat(r.failed()).isEqualTo(1);
    }
}
