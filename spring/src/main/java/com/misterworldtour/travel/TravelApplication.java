package com.misterworldtour.travel;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 고객의 여행 신청 (설계 05장 TravelApplication).
 *
 * <p>신청 고객은 회원 id 로만 가진다 (다른 패키지의 엔티티를 직접 참조·수정하지 않는다).
 * 출발 상태는 허니문만 신청별로 쓰고, 다른 테마는 null 이며 상품 공동 상태를 따른다.
 * 취소는 행을 지우지 않고 cancelledAt 을 기록한다.
 */
@Entity
@Table(name = "travel_application")
public class TravelApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private TravelProduct product;

    @Enumerated(EnumType.STRING)
    @Column(name = "hotel_level", nullable = false, length = 10)
    private HotelLevel hotelLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransportOption transport;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MealOption meal;

    /** 추가 음료 코드를 쉼표로 이은 값 (예: "BEER,WINE"). */
    @Column(name = "extra_drinks", nullable = false, length = 200)
    private String extraDrinks = "";

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(nullable = false)
    private int subtotal;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    @Column(name = "final_amount", nullable = false)
    private int finalAmount;

    @Column(name = "request_key", nullable = false, length = 64, unique = true)
    private String requestKey;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "departure_status", length = 10)
    private DepartureStatus departureStatus;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Traveler> travelers = new ArrayList<>();

    protected TravelApplication() {
    }

    public TravelApplication(Long memberId, TravelProduct product, HotelLevel hotelLevel, TransportOption transport,
                             MealOption meal, String extraDrinks, int unitPrice, int subtotal, int discountAmount,
                             int finalAmount, String requestKey, DepartureStatus departureStatus) {
        this.memberId = memberId;
        this.product = product;
        this.hotelLevel = hotelLevel;
        this.transport = transport;
        this.meal = meal;
        this.extraDrinks = extraDrinks == null ? "" : extraDrinks;
        this.unitPrice = unitPrice;
        this.subtotal = subtotal;
        this.discountAmount = discountAmount;
        this.finalAmount = finalAmount;
        this.requestKey = requestKey;
        this.departureStatus = departureStatus;
    }

    @PrePersist
    void prePersist() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
    }

    public void addTraveler(String name, String contact, boolean applicant) {
        travelers.add(new Traveler(this, name, contact, applicant));
    }

    /** 지금 출발 상태: 허니문은 신청 상태, 그 외는 상품 공동 상태. */
    public DepartureStatus currentDepartureStatus() {
        return product.isHoneymoon() ? departureStatus : product.getDepartureStatus();
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public void cancel() {
        cancelledAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public TravelProduct getProduct() {
        return product;
    }

    public HotelLevel getHotelLevel() {
        return hotelLevel;
    }

    public TransportOption getTransport() {
        return transport;
    }

    public MealOption getMeal() {
        return meal;
    }

    public String getExtraDrinks() {
        return extraDrinks;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public int getSubtotal() {
        return subtotal;
    }

    public int getDiscountAmount() {
        return discountAmount;
    }

    public int getFinalAmount() {
        return finalAmount;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public DepartureStatus getDepartureStatus() {
        return departureStatus;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public List<Traveler> getTravelers() {
        return travelers;
    }

    public int getTravelerCount() {
        return travelers.size();
    }
}
