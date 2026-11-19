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
 * 한 번의 기념품 입고 내역 (설계 6.1 StockReceipt).
 * requestKey 로 같은 입고의 재시도를 구별해 재고가 두 번 늘지 않게 한다.
 */
@Entity
@Table(name = "stock_receipt")
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long receiptId;

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

    StockReceipt(Souvenir souvenir, Long employeeId, int quantity, OffsetDateTime receivedAt, String requestKey) {
        this.souvenir = souvenir;
        this.employeeId = employeeId;
        this.quantity = quantity;
        this.receivedAt = receivedAt;
        this.requestKey = requestKey;
    }

    /** 문제가 있으면 안내 문구, 없으면 null. */
    public String validateReceipt() {
        if (souvenir == null || !souvenir.isActive()) {
            return "등록된 기념품을 찾을 수 없습니다.";
        }
        if (employeeId == null) {
            return "입고 직원 정보가 없습니다. 다시 로그인해 주세요.";
        }
        if (quantity < 1 || quantity > SouvenirManager.MAX_QUANTITY) {
            return "입고 수량은 1 이상 %,d 이하의 정수로 입력해 주세요.".formatted(SouvenirManager.MAX_QUANTITY);
        }
        if (requestKey == null || requestKey.isBlank() || receivedAt == null) {
            return "입고 요청 키가 없습니다. 다시 시도해 주세요.";
        }
        return null;
    }

    /** 직원·품목·수량이 같으면 같은 입고의 재시도다. */
    public boolean matchesRequest(long employeeId, long souvenirId, int quantity) {
        return this.employeeId == employeeId && souvenir.getSouvenirId() == souvenirId && this.quantity == quantity;
    }

    public void recordStockAfter(int stockAfter) {
        this.stockAfter = stockAfter;
    }

    public Long getReceiptId() {
        return receiptId;
    }

    public Long getSouvenirId() {
        return souvenir.getSouvenirId();
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
