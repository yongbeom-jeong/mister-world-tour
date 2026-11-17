package com.misterworldtour.sms;

import com.misterworldtour.sms.SmsClient.RejectedException;
import com.misterworldtour.sms.SmsClient.UnknownResultException;
import com.misterworldtour.sms.SmsLog.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * SMS Notification 서비스 (설계 09장 SMSNotificationManager, Django 판 apps/sms/services.py).
 *
 * <ul>
 *   <li>수신 대상 선정과 출발 확정 판정은 Travel Management 가 한다. 여기서는 받은 대상에게 보내기만 한다.</li>
 *   <li>최초 발송 1회 + 명확한 실패 시 최대 3회 재시도 (전체 최대 4회 외부 호출).</li>
 *   <li>결과 불명(응답 시간 초과 등)은 실패로 바꿔 즉시 재발송하지 않는다 (중복 문자 방지).</li>
 *   <li>신청 요청 안에서 보내므로 한 묶음 전체에 시간 한도를 둔다. 한도 안에 끝나지 못할 시도는 하지 않고 실패로 남긴다.</li>
 *   <li>수신자마다 발송 기록을 남긴다. 신청 저장과 같은 트랜잭션에서 '발송 중'으로 먼저 만들고({@link #prepareDepartureConfirmation}),
 *       저장 뒤 보내며 결과로 바꾼다({@link #deliverDepartureConfirmation}). 기록 저장이 실패해도 발송은 계속한다.</li>
 * </ul>
 */
@Service
public class SmsService {

    public static final int MAX_ATTEMPTS = 4; // 최초 1회 + 재시도 3회
    public static final String SMS_PREFIX = "[미스터월드투어]";
    public static final int SMS_MAX_BYTES = 90; // 단문 한도 (영문·숫자·공백 1byte, 한글 2byte)
    public static final int PAGE_SIZE = 30;
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    public enum DeliveryStatus { PENDING, SUCCESS, FAILED, UNKNOWN, INVALID }

    public record Recipient(Long memberId, String name, String contact) {
    }

    /** 출발 확정 안내 문자. */
    public record DepartureNotice(Long productId, String productName, LocalDate departureDate) {

        /** 단문(SMS, 90byte) 안에 들어가도록 만든다. 길면 이름 → 상품 이름 순으로 줄인다. */
        public String message(Recipient r) {
            String when = departureDate.getMonthValue() + "월 " + departureDate.getDayOfMonth() + "일";
            String full = SMS_PREFIX + " " + r.name() + "님, " + productName + " " + when + " 출발 확정되었습니다.";
            if (smsBytes(full) <= SMS_MAX_BYTES) {
                return full;
            }
            String tail = " " + when + " 출발 확정";
            String shortText = SMS_PREFIX + " " + productName + tail;
            if (smsBytes(shortText) <= SMS_MAX_BYTES) {
                return shortText;
            }
            int budget = SMS_MAX_BYTES - smsBytes(SMS_PREFIX + " .." + tail);
            StringBuilder product = new StringBuilder();
            for (int cp : productName.codePoints().toArray()) {
                String next = product + new String(Character.toChars(cp));
                if (smsBytes(next) > budget) {
                    break;
                }
                product.appendCodePoint(cp);
            }
            return SMS_PREFIX + " " + product + ".." + tail;
        }
    }

    public static int smsBytes(String text) {
        return text.codePoints().map(c -> c < 128 ? 1 : 2).sum();
    }

    /** 문자 1건의 진행 상태. */
    public static final class Message {
        final Recipient recipient;
        final String text;
        DeliveryStatus status = DeliveryStatus.PENDING;
        int attemptCount;
        String lastError = "";
        String providerRef = "";
        Long logId;

        Message(Recipient recipient, String text) {
            this.recipient = recipient;
            this.text = text;
        }

        void recordResult(DeliveryStatus s, String error) {
            attemptCount++;
            status = s;
            lastError = error == null ? "" : error;
        }

        public Recipient recipient() {
            return recipient;
        }

        public String text() {
            return text;
        }

        public DeliveryStatus status() {
            return status;
        }

        public int attemptCount() {
            return attemptCount;
        }

        public String lastError() {
            return lastError;
        }

        public Long logId() {
            return logId;
        }
    }

    /** 보내기 전에 발송 기록을 만들어 둔 출발 확정 문자 묶음. */
    public record DeparturePlan(DepartureNotice notice, List<Message> messages, List<Recipient> invalid) {
    }

    /** 묶음 발송 결과. */
    public record BatchResult(List<Message> messages, List<Recipient> invalid) {

        public static BatchResult allInvalid(List<Recipient> recipients) {
            return new BatchResult(List.of(), List.copyOf(recipients));
        }

        int count(DeliveryStatus s) {
            return (int) messages.stream().filter(m -> m.status == s).count();
        }

        public int success() {
            return count(DeliveryStatus.SUCCESS);
        }

        public int failed() {
            return count(DeliveryStatus.FAILED) + invalid.size();
        }

        public int unknown() {
            return count(DeliveryStatus.UNKNOWN);
        }

        public int total() {
            return messages.size() + invalid.size();
        }

        public String summary() {
            if (total() == 0) {
                return "보낼 문자가 없습니다.";
            }
            List<String> parts = new ArrayList<>();
            parts.add("성공 " + success() + "건");
            if (failed() > 0) {
                parts.add("실패 " + failed() + "건");
            }
            if (unknown() > 0) {
                parts.add("결과 확인 필요 " + unknown() + "건");
            }
            return "출발 확정 문자: " + String.join(", ", parts);
        }
    }

    public static class ResendException extends RuntimeException {
        public ResendException(String message) {
            super(message);
        }
    }

    private final SmsLogRepository logs;
    private final SmsLogWriter writer;
    private final SmsClient client;
    private final SmsProperties properties;
    private final TransactionTemplate tx;

    SmsService(SmsLogRepository logs, SmsLogWriter writer, SmsClient client, SmsProperties properties,
               PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.writer = writer;
        this.client = client;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- 발송

    /** 남은 시간 안에 외부 호출 1회(최대 callTimeout)를 끝낼 수 있는지. 시간 단위는 나노초. */
    private static boolean attemptFits(long started, long limit, long callTimeout, LongSupplier clock) {
        return clock.getAsLong() - started + callTimeout <= limit;
    }

    /** 최초 1회 + 명확한 실패 시 재시도(최대 4회). 시간 한도를 넘길 시도는 하지 않는다. msg 를 갱신한다. */
    private void deliver(SmsClient c, Message msg, long started, Duration limit, LongSupplier clock, Long productId) {
        long callTimeout = c.timeout().toNanos();
        int calls = 0;
        Recipient r = msg.recipient;
        while (true) {
            if (!attemptFits(started, limit.toNanos(), callTimeout, clock)) {
                if (calls == 0) { // 이번에 한 번도 못 보냄: 보내지 않았음이 확실하므로 실패
                    msg.status = DeliveryStatus.FAILED;
                    msg.lastError = "발송 시간 한도(" + limit.toSeconds() + "초) 초과로 보내지 않음";
                    log.warn("문자 발송 시간 한도 초과로 보내지 않음 product={} member={}", productId, r.memberId());
                }
                break; // 재시도 중이면 마지막 실패 상태를 그대로 둔다
            }
            boolean rejected = false;
            calls++;
            try {
                String ref = c.send(r.contact(), msg.text);
                msg.recordResult(DeliveryStatus.SUCCESS, "");
                msg.providerRef = ref == null ? "" : ref;
            } catch (UnknownResultException e) {
                msg.recordResult(DeliveryStatus.UNKNOWN, e.getMessage());
            } catch (RuntimeException e) { // SendException 및 연결 오류 등 명확한 실패
                msg.recordResult(DeliveryStatus.FAILED, e.getMessage());
                rejected = e instanceof RejectedException; // 보내기 전 거부: 다시 해도 같으므로 재시도 안 함
            }
            if (msg.status != DeliveryStatus.SUCCESS) {
                log.warn("문자 발송 {} product={} member={} attempt={} error={}", msg.status, productId, r.memberId(),
                        msg.attemptCount, msg.lastError);
            }
            if (rejected || msg.status != DeliveryStatus.FAILED || calls >= MAX_ATTEMPTS) {
                break;
            }
        }
    }

    // ---------------------------------------------------------------- 발송 기록 (저장 실패해도 발송에는 영향 없음)

    private void logStart(Message msg, DepartureNotice notice) {
        Recipient r = msg.recipient;
        try {
            // 저장점: 실패해도 호출자 트랜잭션(신청 저장)은 계속된다
            msg.logId = writer.insertStart(r.memberId(), r.name(), r.contact(), notice.productId(),
                    notice.productName(), msg.text, Status.valueOf(msg.status.name()), msg.lastError);
        } catch (RuntimeException e) {
            log.error("문자 발송 기록 저장 실패 member={}", r.memberId(), e);
        }
    }

    private void logFinish(Message msg, Long resentBy) {
        if (msg.logId == null) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> logs.findById(msg.logId).ifPresent(l -> {
                l.applyResult(Status.valueOf(msg.status.name()), msg.attemptCount, msg.lastError, msg.providerRef);
                if (resentBy != null) {
                    l.countResend(resentBy);
                }
            }));
        } catch (RuntimeException e) {
            log.error("문자 발송 기록 갱신 실패 log={}", msg.logId, e);
        }
    }

    /**
     * 수신자마다 발송 기록을 '발송 중'(정보 누락은 '정보 누락')으로 먼저 만든다. 외부 호출은 하지 않는다.
     * 신청 저장과 같은 트랜잭션에서 부르면 신청과 기록이 함께 저장된다. 그래서 저장 직후 보내기 전에 서버가
     * 멈춰도 기록이 남고, 직원 문자 기록 화면에 '발송 확인 필요'로 나타나 다시 보낼 수 있다 (설계 05장 §2.2).
     */
    public DeparturePlan prepareDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice) {
        List<Message> messages = new ArrayList<>();
        List<Recipient> invalid = new ArrayList<>();
        for (Recipient r : recipients) {
            boolean missing = r.contact() == null || r.contact().isBlank() || notice.productName() == null
                    || notice.productName().isBlank() || notice.departureDate() == null;
            if (missing) {
                log.warn("문자 대상 정보 누락 product={} member={}", notice.productId(), r.memberId());
                invalid.add(r);
                Message m = new Message(r, notice.departureDate() == null ? "" : notice.message(r));
                m.status = DeliveryStatus.INVALID;
                m.lastError = "연락처 또는 상품 정보 누락";
                logStart(m, notice);
                continue;
            }
            Message msg = new Message(r, notice.message(r));
            logStart(msg, notice);
            messages.add(msg);
        }
        return new DeparturePlan(notice, messages, invalid);
    }

    /** 기록을 만들지 못했을 때 쓰는 계획 (기록 없이 보내기만). */
    public DeparturePlan planWithoutLogs(List<Recipient> recipients, DepartureNotice notice) {
        List<Message> messages = recipients.stream().filter(r -> r.contact() != null && !r.contact().isBlank())
                .map(r -> new Message(r, notice.message(r))).toList();
        return new DeparturePlan(notice, new ArrayList<>(messages), new ArrayList<>());
    }

    public BatchResult deliverDepartureConfirmation(DeparturePlan plan) {
        return deliverDepartureConfirmation(plan, client, System::nanoTime);
    }

    /** 준비한 묶음을 실제로 보내고 기록을 결과로 바꾼다 (재시도 규칙·시간 한도 적용). */
    public BatchResult deliverDepartureConfirmation(DeparturePlan plan, SmsClient c, LongSupplier clock) {
        DepartureNotice notice = plan.notice();
        List<Message> done = new ArrayList<>();
        long started = clock.getAsLong();
        for (Message msg : plan.messages()) {
            if (msg.logId == null) { // 준비 때 기록을 못 만들었으면 지금 다시 시도
                logStart(msg, notice);
            }
            deliver(c, msg, started, properties.batchTimeLimit(), clock, notice.productId());
            logFinish(msg, null);
            done.add(msg);
        }
        BatchResult result = new BatchResult(done, List.copyOf(plan.invalid()));
        log.info("문자 발송 결과 product={} {}", notice.productId(), result.summary());
        return result;
    }

    /** 기록 준비와 발송을 한 번에 (트랜잭션 밖에서 바로 보낼 때). */
    public BatchResult sendDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice, SmsClient c,
                                                 LongSupplier clock) {
        return deliverDepartureConfirmation(prepareDepartureConfirmation(recipients, notice), c, clock);
    }

    /** 시험 발송 1회 (재시도 없음). */
    public String sendTest(String contact, String text) {
        return client.send(contact, text);
    }

    public String clientName() {
        return client.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- 직원 조회·재발송

    /** 발송 기록 (최근 순). problemsOnly 면 실패·결과 불명·멈춘 발송 중만. page 는 0부터. */
    public Page<SmsLog> loadLogs(boolean problemsOnly, int page) {
        PageRequest req = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        return problemsOnly ? logs.findProblems(OffsetDateTime.now().minus(SmsLog.STALE_PENDING), req)
                : logs.findAllByOrderByCreatedAtDescIdDesc(req);
    }

    public long problemCount() {
        return logs.findProblems(OffsetDateTime.now().minus(SmsLog.STALE_PENDING), PageRequest.of(0, 1))
                .getTotalElements();
    }

    public SmsLog resendLoggedSms(long logId, long employeeId, boolean confirmDuplicate) {
        return resendLoggedSms(logId, employeeId, confirmDuplicate, client, System::nanoTime);
    }

    /**
     * 실패·결과 불명 기록을 같은 문구로 다시 보낸다 (재시도 규칙·시간 한도 동일).
     * 결과 불명은 이미 받았을 수 있어 confirmDuplicate 가 있어야 보낸다.
     * 두 직원이 동시에 눌러도 한 번만 보내도록 잠근 상태에서 '발송 중'으로 바꾼 뒤 보낸다.
     */
    public SmsLog resendLoggedSms(long logId, long employeeId, boolean confirmDuplicate, SmsClient c, LongSupplier clock) {
        SmsLog locked = tx.execute(s -> {
            SmsLog l = logs.findForUpdate(logId).orElseThrow(() -> new ResendException("문자 기록을 찾을 수 없습니다."));
            if (!l.isNeedsAttention()) {
                throw new ResendException("'" + l.getStatus().getLabel() + "' 상태라 다시 보낼 수 없습니다.");
            }
            if (l.getContact().isBlank()) {
                throw new ResendException("연락처가 없어 보낼 수 없습니다.");
            }
            if (l.isMayBeDelivered() && !confirmDuplicate) {
                throw new ResendException("이미 받았을 수 있는 문자입니다. 중복 수신 가능성을 확인한 뒤 다시 보내 주세요.");
            }
            l.markPending();
            return l;
        });
        Message msg = new Message(new Recipient(locked.getMemberId(), locked.getRecipientName(), locked.getContact()),
                locked.getMessage());
        msg.attemptCount = locked.getAttemptCount();
        msg.logId = locked.getId();
        deliver(c, msg, clock.getAsLong(), properties.batchTimeLimit(), clock, locked.getProductId());
        logFinish(msg, employeeId);
        SmsLog after = logs.findById(logId).orElseThrow();
        log.info("문자 재발송 log={} status={} employee={}", logId, after.getStatus(), employeeId);
        return after;
    }
}
