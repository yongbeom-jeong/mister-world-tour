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

/** 문자 1건(수신자 1명)의 발송 기록 (Django 판 SMSLog). 자동 재시도와 직원 재발송 결과를 같은 행에 누적한다. */
@Entity
@Table(name = "sms_log")
public class SmsLog {

    /** '발송 중'으로 이만큼 지나면 처리 도중 멈춘 것으로 보고 직원이 확인·재발송한다. */
    public static final Duration STALE_PENDING = Duration.ofMinutes(5);

    public enum Status {
        PENDING("발송 중"), SUCCESS("성공"), FAILED("실패"), UNKNOWN("결과 확인 필요"), INVALID("정보 누락");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
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

    /** 업체 접수 번호 (솔라피 groupId). */
    @Column(name = "provider_ref", nullable = false, length = 100)
    private String providerRef = "";

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    @Column(name = "resent_by_id")
    private Long resentById;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SmsLog() {
    }

    SmsLog(Long memberId, String recipientName, String contact, Long productId, String productName, String message,
           Status status, String lastError) {
        this.memberId = memberId;
        this.recipientName = cut(recipientName, 50);
        this.contact = cut(contact, 20);
        this.productId = productId;
        this.productName = cut(productName, 100);
        this.message = message == null ? "" : message;
        this.status = status;
        this.lastError = lastError == null ? "" : lastError;
    }

    static String cut(String s, int max) {
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

    void markPending() {
        status = Status.PENDING;
        updatedAt = OffsetDateTime.now(); // 상태가 같아도 '멈춤' 판정 시각을 새로 시작
    }

    void applyResult(Status status, int attemptCount, String lastError, String providerRef) {
        this.status = status;
        this.attemptCount = attemptCount;
        this.lastError = lastError == null ? "" : lastError;
        this.providerRef = cut(providerRef, 100);
        this.updatedAt = OffsetDateTime.now();
    }

    void countResend(long employeeId) {
        resendCount++;
        resentById = employeeId;
    }

    /** '발송 중'인데 일정 시간 넘게 바뀌지 않음 (보내기 전·도중에 서버가 멈춘 경우). */
    public boolean isStalePending() {
        return status == Status.PENDING && updatedAt.isBefore(OffsetDateTime.now().minus(STALE_PENDING));
    }

    /** 직원이 확인·재발송할 대상. */
    public boolean isNeedsAttention() {
        return status == Status.FAILED || status == Status.UNKNOWN || isStalePending();
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

    public String getProviderRef() {
        return providerRef;
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
