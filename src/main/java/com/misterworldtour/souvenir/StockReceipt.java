package com.misterworldtour.souvenir;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 한 번의 기념품 입고 내역 (설계 06장 StockReceipt).
 * requestKey 로 같은 입고의 재시도를 구별해 재고가 두 번 늘지 않게 한다. 입고 직원은 회원 id 로만 가진다.
 */
@Entity
@Table(name = "stock_receipt")
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "souvenir_id")
    private Souvenir souvenir;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "request_key", nullable = false, length = 64, unique = true)
    private String requestKey;

    @Column(name = "stock_after", nullable = false)
    private int stockAfter;

    protected StockReceipt() {
    }

    StockReceipt(Souvenir souvenir, Long employeeId, int quantity, OffsetDateTime receivedAt, String requestKey,
                 int stockAfter) {
        this.souvenir = souvenir;
        this.employeeId = employeeId;
        this.quantity = quantity;
        this.receivedAt = receivedAt;
        this.requestKey = requestKey;
        this.stockAfter = stockAfter;
    }

    boolean matchesRequest(long employeeId, long souvenirId, int quantity) {
        return this.employeeId == employeeId && souvenir.getId() == souvenirId && this.quantity == quantity;
    }

    public Long getId() {
        return id;
    }

    public Souvenir getSouvenir() {
        return souvenir;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public int getQuantity() {
        return quantity;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public int getStockAfter() {
        return stockAfter;
    }
}
