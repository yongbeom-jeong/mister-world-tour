package com.misterworldtour.sms;

import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSMessage.SendResult;
import com.misterworldtour.sms.SMSMessage.Status;
import com.misterworldtour.sms.SMSNotificationAPI.DepartureNotice;
import com.misterworldtour.sms.SMSNotificationAPI.SMSBatchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 출발 확정 문자 발송 (설계 9.1 SMSNotificationManager).
 * 신청 요청 안에서 보내므로 한 묶음 전체에 시간 한도를 둔다. 기록 저장이 실패해도 발송은 계속한다.
 */
@Service
public class SMSNotificationManager implements SMSNotificationAPI {

    public static final int PAGE_SIZE = 30;
    private static final Logger log = LoggerFactory.getLogger(SMSNotificationManager.class);

    private final SMSMessageDataAccessAPI dataAccess;
    private final SMSServiceClient client;
    private final SMSProperties properties;
    private final TransactionTemplate tx;

    SMSNotificationManager(SMSMessageDataAccessAPI dataAccess, SMSServiceClient client, SMSProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.dataAccess = dataAccess;
        this.client = client;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public SMSBatchResult sendDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice) {
        return sendDepartureConfirmation(prepareDepartureConfirmation(recipients, notice));
    }

    /**
     * 발송 기록을 Pending 으로 먼저 저장한다 (외부 호출 없음). 보내기 전에 서버가 멈춰도 기록이 남아 다시 보낼 수 있다.
     * 연락처·상품 정보가 없는 대상은 invalid 로 (상태도 11.1).
     */
    public DeparturePlan prepareDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice) {
        List<SMSMessage> messages = new ArrayList<>();
        List<Recipient> invalid = new ArrayList<>();
        for (Recipient r : recipients) {
            boolean missing = r.contact() == null || r.contact().isBlank() || notice.productName() == null
                    || notice.productName().isBlank() || notice.departureDate() == null;
            if (missing) {
                log.warn("문자 대상 정보 누락(입력 오류) product={} member={}", notice.productId(), r.memberId());
                invalid.add(r);
                continue;
            }
            SMSMessage msg = new SMSMessage(r, notice.message(r), notice.productId(), notice.productName());
            insertLog(msg);
            messages.add(msg);
        }
        return new DeparturePlan(notice, messages, invalid);
    }

    public DeparturePlan planWithoutLogs(List<Recipient> recipients, DepartureNotice notice) {
        List<SMSMessage> messages = recipients.stream().filter(r -> r.contact() != null && !r.contact().isBlank())
                .map(r -> new SMSMessage(r, notice.message(r), notice.productId(), notice.productName())).toList();
        return new DeparturePlan(notice, new ArrayList<>(messages), new ArrayList<>());
    }

    public SMSBatchResult sendDepartureConfirmation(DeparturePlan plan) {
        return sendDepartureConfirmation(plan, client, System::nanoTime);
    }

    SMSBatchResult sendDepartureConfirmation(DeparturePlan plan, SMSServiceClient c, LongSupplier clock) {
        DepartureNotice notice = plan.notice();
        List<SMSMessage> done = new ArrayList<>();
        long started = clock.getAsLong();
        for (SMSMessage msg : plan.messages()) {
            if (msg.getId() == null) { // 준비 때 기록을 못 만들었으면 지금 다시 시도
                insertLog(msg);
            }
            deliver(c, msg, started, properties.batchTimeLimit(), clock);
            saveLog(msg, null);
            done.add(msg);
        }
        SMSBatchResult result = new SMSBatchResult(done, List.copyOf(plan.invalid()));
        log.info("문자 발송 결과 product={} {}", notice.productId(), result.summary());
        return result;
    }

    /** 테스트용: 클라이언트·시계를 바꿔 부른다. */
    SMSBatchResult sendDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice, SMSServiceClient c,
                                             LongSupplier clock) {
        return sendDepartureConfirmation(prepareDepartureConfirmation(recipients, notice), c, clock);
    }

    private static SendResult sendSMS(SMSServiceClient c, String contact, String message) {
        try {
            return SendResult.success(c.sendSMS(contact, message));
        } catch (SMSServiceClient.UnknownResultException e) {
            return SendResult.unknown(e.getMessage());
        } catch (SMSServiceClient.RejectedException e) { // 다시 해도 같으므로 재시도 안 함
            return SendResult.failure(e.getMessage(), false);
        } catch (RuntimeException e) {
            return SendResult.failure(e.getMessage(), true);
        }
    }

    /** 남은 시간 안에 외부 호출 1회(최대 callTimeout)를 끝낼 수 있는지 (나노초). */
    private static boolean attemptFits(long started, long limit, long callTimeout, LongSupplier clock) {
        return clock.getAsLong() - started + callTimeout <= limit;
    }

    /** 최초 1회 + canRetry 동안 재시도. 시간 한도를 넘길 시도는 하지 않는다. */
    private void deliver(SMSServiceClient c, SMSMessage msg, long started, Duration limit, LongSupplier clock) {
        long callTimeout = c.timeout().toNanos();
        boolean first = true;
        while (first || msg.canRetry()) {
            if (!attemptFits(started, limit.toNanos(), callTimeout, clock)) {
                if (first) { // 한 번도 안 보냈으니 확실한 실패
                    msg.markNotSent("발송 시간 한도(" + limit.toSeconds() + "초) 초과로 보내지 않음");
                    log.warn("문자 발송 시간 한도 초과로 보내지 않음 product={} member={}", msg.getProductId(),
                            msg.getMemberId());
                }
                break; // 재시도 중이면 마지막 실패 상태를 그대로 둔다
            }
            first = false;
            msg.recordResult(sendSMS(c, msg.getContact(), msg.getMessage()));
            if (msg.getStatus() != Status.SUCCESS) {
                log.warn("문자 발송 {} product={} member={} attempt={} error={}", msg.getStatus(), msg.getProductId(),
                        msg.getMemberId(), msg.getAttemptCount(), msg.getLastError());
            }
        }
    }

    private void insertLog(SMSMessage msg) {
        try {
            dataAccess.insertSMSMessage(msg);
        } catch (RuntimeException e) {
            log.error("문자 발송 기록 저장 실패 member={}", msg.getMemberId(), e);
        }
    }

    private void saveLog(SMSMessage msg, Long resentBy) {
        if (msg.getId() == null) {
            return;
        }
        try {
            dataAccess.saveSMSMessageResult(msg, resentBy);
        } catch (RuntimeException e) {
            log.error("문자 발송 기록 갱신 실패 log={}", msg.getId(), e);
        }
    }

    /** 시험 발송 1회 (재시도 없음). */
    public String sendTest(String contact, String text) {
        return client.sendSMS(contact, text);
    }

    public String clientName() {
        return client.getClass().getSimpleName();
    }

    /** 최근 순. problemsOnly 면 실패·결과 불명·멈춘 것만. page 는 0부터. */
    public Page<SMSMessage> loadMessages(boolean problemsOnly, int page) {
        return problemsOnly ? dataAccess.findProblemSMSMessages(staleBefore(), page, PAGE_SIZE)
                : dataAccess.findSMSMessages(page, PAGE_SIZE);
    }

    public long problemCount() {
        return dataAccess.findProblemSMSMessages(staleBefore(), 0, 1).getTotalElements();
    }

    private static OffsetDateTime staleBefore() {
        return OffsetDateTime.now().minus(SMSMessage.STALE_PENDING);
    }

    public SMSMessage resendMessage(long messageId, long employeeId, boolean confirmDuplicate) {
        return resendMessage(messageId, employeeId, confirmDuplicate, client, System::nanoTime);
    }

    /**
     * 결과 불명은 이미 받았을 수 있어 confirmDuplicate 가 있어야 보낸다.
     * 두 직원이 동시에 눌러도 한 번만 보내도록 잠근 상태에서 Pending 으로 바꾼 뒤 보낸다.
     */
    SMSMessage resendMessage(long messageId, long employeeId, boolean confirmDuplicate, SMSServiceClient c,
                             LongSupplier clock) {
        SMSMessage locked = tx.execute(s -> {
            SMSMessage m = dataAccess.findSMSMessageForUpdate(messageId)
                    .orElseThrow(() -> new ResendException("문자 기록을 찾을 수 없습니다."));
            if (!m.isNeedsAttention()) {
                throw new ResendException("'" + m.getStatus().getLabel() + "' 상태라 다시 보낼 수 없습니다.");
            }
            if (m.getContact().isBlank()) {
                throw new ResendException("연락처가 없어 보낼 수 없습니다.");
            }
            if (m.isMayBeDelivered() && !confirmDuplicate) {
                throw new ResendException("이미 받았을 수 있는 문자입니다. 중복 수신 가능성을 확인한 뒤 다시 보내 주세요.");
            }
            m.markPending();
            return m;
        });
        deliver(c, locked, clock.getAsLong(), properties.batchTimeLimit(), clock);
        saveLog(locked, employeeId);
        SMSMessage after = dataAccess.findSMSMessage(messageId).orElseThrow();
        log.info("문자 재발송 log={} status={} employee={}", messageId, after.getStatus(), employeeId);
        return after;
    }
}
