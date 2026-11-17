package com.misterworldtour.sms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 고객 한 명에게 보낼 문자와 발송 상태 (설계 9.1 SMSMessage, 11.1 상태도).
 *
 * <p>상태는 Pending(발송 대기) → Success / Failure / Unknown. 발송 시도마다 {@link #recordResult} 로 결과를 반영하고
 * 시도 횟수를 1 늘린다. {@link #canRetry} 는 명확한 실패이고 시도가 4회 미만일 때만 참이다 (최초 1회 + 재시도 최대 3회).
 * 수신자마다 1행으로 저장해 발송 기록으로 쓰고, 직원 재발송 결과도 같은 행에 누적한다.
 */
@Entity
@Table(name = "sms_log")
public class SMSMessage {

    /** 최초 발송 1회 + 재시도 3회. */
    public static final int MAX_ATTEMPTS = 4;
    /** 'Pending' 으로 이만큼 지나면 처리 도중 멈춘 것으로 보고 직원이 확인·재발송한다. */
    public static final Duration STALE_PENDING = Duration.ofMinutes(5);

    public enum Status {
        PENDING("발송 중"), SUCCESS("성공"), FAILURE("실패"), UNKNOWN("결과 확인 필요"), INVALID("정보 누락");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 외부 발송 1회의 결과 (SMSServiceClient 응답을 정리한 값). */
    public record SendResult(Status status, String serviceRequestId, String error, boolean retryable) {

        public static SendResult success(String serviceRequestId) {
            return new SendResult(Status.SUCCESS, serviceRequestId == null ? "" : serviceRequestId, "", false);
        }

        /** 명확한 실패. retryable 이 거짓이면(보내기 전 거부 등) 다시 보내도 같으므로 재시도하지 않는다. */
        public static SendResult failure(String error, boolean retryable) {
            return new SendResult(Status.FAILURE, "", error, retryable);
        }

        public static SendResult unknown(String error) {
            return new SendResult(Status.UNKNOWN, "", error, false);
        }
    }

    /** 문자를 받을 고객 (회원 번호·성명·연락처). */
    public record Recipient(Long memberId, String name, String contact) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(nullable = false, length = 20)
    private String contact = "";

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", nullable = false, columnDefinition = "TEXT")
    private String lastError = "";

    /** 외부 서비스 요청 식별값 (솔라피 groupId). */
    @Column(name = "provider_ref", nullable = false, length = 100)
    private String serviceRequestId = "";

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    @Column(name = "resent_by_id")
    private Long resentById;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 마지막 시도가 다시 보내도 소용없는 거부였는지 (저장하지 않음). */
    private transient boolean lastRejected;

    /** 이번 발송 회차를 시작할 때의 시도 횟수 (직원 재발송은 새 회차로 최대 4회). 저장하지 않음. */
    private transient int roundStartAttempts;

    protected SMSMessage() {
    }

    /** 수신 정보와 본문을 정하고 Pending·시도 0회로 시작한다. productId·productName 은 기록 표시용. */
    public SMSMessage(Recipient recipient, String message, Long productId, String productName) {
        this.memberId = recipient.memberId();
        this.recipientName = cut(recipient.name(), 50);
        this.contact = cut(recipient.contact(), 20);
        this.productId = productId;
        this.productName = cut(productName, 100);
        this.message = message == null ? "" : message;
        this.status = Status.PENDING;
        this.attemptCount = 0;
    }

    public static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ---------------------------------------------------------------- RecordResult · CanRetry

    /** 외부 발송 1회의 결과를 반영하고 시도 횟수를 1 늘린다. */
    public void recordResult(SendResult result) {
        attemptCount++;
        status = result.status();
        lastError = result.error() == null ? "" : result.error();
        lastRejected = result.status() == Status.FAILURE && !result.retryable();
        if (result.status() == Status.SUCCESS) {
            serviceRequestId = cut(result.serviceRequestId(), 100);
        }
        updatedAt = OffsetDateTime.now();
    }

    /**
     * 명확한 실패이고 (이번 발송 회차의) 시도 횟수가 4회 미만이면 다시 보낼 수 있다.
     * 성공·결과 불명·한도 도달·보내기 전 거부는 불가. 직원 재발송은 누적 횟수와 별개로 새 회차를 시작한다.
     */
    public boolean canRetry() {
        return status == Status.FAILURE && !lastRejected && attemptCount - roundStartAttempts < MAX_ATTEMPTS;
    }

    /** 보낼 수 없는 대상(연락처·상품 정보 누락)으로 표시한다. */
    public void markInvalid(String reason) {
        status = Status.INVALID;
        lastError = reason;
    }

    /** 보내지 않고 실패로 끝낸다 (시간 한도 등). 시도 횟수는 그대로. */
    public void markNotSent(String reason) {
        status = Status.FAILURE;
        lastError = reason;
    }

    /** 직원 재발송 시작: 다시 발송 중으로 바꾼다. 상태가 같아도 '멈춤' 판정 시각을 새로 시작한다. */
    public void markPending() {
        status = Status.PENDING;
        lastRejected = false;
        roundStartAttempts = attemptCount;
        updatedAt = OffsetDateTime.now();
    }

    /** 다른 객체(발송 중 메모리에서 바뀐 결과)의 상태를 이 행에 옮긴다. */
    public void copyResultFrom(SMSMessage other) {
        this.status = other.status;
        this.attemptCount = other.attemptCount;
        this.lastError = other.lastError;
        this.serviceRequestId = other.serviceRequestId;
        this.updatedAt = OffsetDateTime.now();
    }

    public void countResend(long employeeId) {
        resendCount++;
        resentById = employeeId;
    }

    /** 저장 후 받은 기록 번호를 메모리의 객체에 붙인다 (JDBC 로 저장한 경우). */
    public void assignId(long id) {
        this.id = id;
    }

    /** 'Pending' 인데 일정 시간 넘게 바뀌지 않음 (보내기 전·도중에 서버가 멈춘 경우). */
    public boolean isStalePending() {
        return status == Status.PENDING && updatedAt != null
                && updatedAt.isBefore(OffsetDateTime.now().minus(STALE_PENDING));
    }

    /** 직원이 확인·재발송할 대상. */
    public boolean isNeedsAttention() {
        return status == Status.FAILURE || status == Status.UNKNOWN || isStalePending();
    }

    /** 이미 받았을 수 있는 상태 (재발송하면 중복 수신 가능). */
    public boolean isMayBeDelivered() {
        return status == Status.UNKNOWN || isStalePending();
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getContact() {
        return contact;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getMessage() {
        return message;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public String getServiceRequestId() {
        return serviceRequestId;
    }

    public int getResendCount() {
        return resendCount;
    }

    public Long getResentById() {
        return resentById;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
