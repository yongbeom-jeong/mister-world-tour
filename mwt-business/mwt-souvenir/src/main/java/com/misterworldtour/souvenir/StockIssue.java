package com.misterworldtour.souvenir;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 출발 확정으로 차감한 기념품 (품목·신청마다 1행, 여행자 1인당 1개).
 * 신청은 id 로만 가진다 (Travel 패키지의 엔티티를 직접 참조하지 않음).
 */
@Entity
@Table(name = "stock_issue")
public class StockIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "souvenir_id")
    private Souvenir souvenir;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "stock_after", nullable = false)
    private int stockAfter;

    protected StockIssue() {
    }

    StockIssue(Souvenir souvenir, Long applicationId, int quantity, int stockAfter) {
        this.souvenir = souvenir;
        this.applicationId = applicationId;
        this.quantity = quantity;
        this.stockAfter = stockAfter;
    }

    @PrePersist
    void prePersist() {
        if (issuedAt == null) {
            issuedAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Souvenir getSouvenir() {
        return souvenir;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public int getQuantity() {
        return quantity;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public int getStockAfter() {
        return stockAfter;
    }
}
