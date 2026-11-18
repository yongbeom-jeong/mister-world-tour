package com.misterworldtour.travel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 고객의 이전 여행 이력 (설계 5.1 TravelHistory). 여행 당시 상품 이름·여행 기간·투어 등급·가격과 완료 여부·완료일·
 * 완료 여행 식별값을 보관한다.
 */
@Entity
@Table(name = "travel_history")
public class TravelHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long historyId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 완료 여행 식별값. 신청에서 만든 이력은 "product-상품id" (고객·상품마다 1건). */
    @Column(name = "completed_travel_id", nullable = false, length = 64)
    private String completedTravelId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tour_level", nullable = false, length = 10)
    private TourLevel tourLevel;

    /** 여행 기간 (일 수). */
    @Column(name = "travel_days", nullable = false)
    private int travelPeriod;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private LocalDate completedAt;

    protected TravelHistory() {
    }

    public TravelHistory(Long memberId, String completedTravelId, String productName, TourLevel tourLevel,
                         int travelPeriod, int price, boolean completed, LocalDate completedAt) {
        this.memberId = memberId;
        this.completedTravelId = completedTravelId;
        this.productName = productName;
        this.tourLevel = tourLevel;
        this.travelPeriod = travelPeriod;
        this.price = price;
        this.completed = completed;
        this.completedAt = completedAt;
    }

    /** 신청일 기준 최근 3년 이내에 완료된 여행인지 (미래 완료일·미완료는 제외). */
    public boolean isCompletedWithinThreeYears(LocalDate applicationDate) {
        if (!completed || completedAt == null || completedAt.isAfter(applicationDate)) {
            return false;
        }
        // 2월 29일은 3년 전 2월 28일로 (Java 의 minusYears 가 같은 방식으로 맞춘다)
        LocalDate cutoff = applicationDate.minusYears(TravelRules.REGULAR_YEARS);
        return !completedAt.isBefore(cutoff);
    }

    public Long getHistoryId() {
        return historyId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getCompletedTravelId() {
        return completedTravelId;
    }

    public String getProductName() {
        return productName;
    }

    public TourLevel getTourLevel() {
        return tourLevel;
    }

    public int getTravelPeriod() {
        return travelPeriod;
    }

    public int getPrice() {
        return price;
    }

    public boolean isCompleted() {
        return completed;
    }

    public LocalDate getCompletedAt() {
        return completedAt;
    }
}
