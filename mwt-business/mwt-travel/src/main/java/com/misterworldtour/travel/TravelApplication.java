package com.misterworldtour.travel;

import com.misterworldtour.members.MemberManagementAPI;

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

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 고객의 여행 신청 (설계 5.1 TravelApplication).
 * 출발 상태는 허니문만 신청별로 쓰고, 다른 테마는 null(상품 공동 상태를 따름). 취소는 행을 지우지 않고 cancelledAt 기록.
 */
@Entity
@Table(name = "travel_application")
public class TravelApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long applicationId;

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

    public TravelApplication(Long memberId, TravelProduct product, TravelConfiguration configuration,
                             TravelPriceCalculator.PriceBreakdown price, String requestKey) {
        this.memberId = memberId;
        this.product = product;
        this.hotelLevel = HotelLevel.valueOf(configuration.hotelLevel());
        this.transport = TransportOption.valueOf(configuration.transport());
        this.meal = MealOption.valueOf(configuration.meal());
        this.extraDrinks = configuration.drinksCsv();
        this.unitPrice = price.unitPrice();
        this.subtotal = price.subtotal();
        this.discountAmount = price.discountAmount();
        this.finalAmount = price.finalAmount();
        this.requestKey = requestKey;
    }

    @PrePersist
    void prePersist() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
    }

    public record TravelerInput(String name, String contact, boolean applicant) implements Serializable {
    }

    static final int MAX_TRAVELER_NAME = 50;

    /** 여행자 정보 검증 (설계 ValidateTravelers). 첫 행은 신청 고객 본인. 성명 필수, 연락처 선택. 허니문은 정확히 2명. */
    public static List<TravelerInput> validateTravelers(Theme theme, List<TravelerInput> rows) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<TravelerInput> cleaned = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String name = strip(rows.get(i).name());
            String contactRaw = strip(rows.get(i).contact());
            if (name.isEmpty() && contactRaw.isEmpty()) {
                continue;
            }
            String key = "traveler_" + i;
            if (name.isEmpty()) {
                errors.put(key, (i + 1) + "번째 여행자의 성명을 입력해 주세요.");
                continue;
            }
            if (name.length() > MAX_TRAVELER_NAME) {
                errors.put(key, (i + 1) + "번째 여행자의 성명이 너무 깁니다.");
                continue;
            }
            String contact = "";
            if (!contactRaw.isEmpty()) {
                Optional<String> normalized = MemberManagementAPI.normalizeContact(contactRaw);
                if (normalized.isEmpty()) {
                    errors.put(key, (i + 1) + "번째 여행자의 연락처 형식이 올바르지 않습니다. 예: 010-1234-5678");
                    continue;
                }
                contact = normalized.get();
            }
            cleaned.add(new TravelerInput(name, contact, i == 0));
        }
        if (!errors.isEmpty()) {
            throw new BookingException("여행자 정보를 확인해 주세요.", errors);
        }
        if (theme == Theme.HONEYMOON && cleaned.size() != TravelRules.HONEYMOON_PARTY_SIZE) {
            throw new BookingException("허니문 상품은 신청 고객 1명과 동반 여행자 1명, 정확히 2명이어야 합니다.",
                    Map.of("travelers", "동반 여행자 1명의 정보를 입력해 주세요."));
        }
        if (cleaned.isEmpty()) {
            throw new BookingException("여행자를 1명 이상 입력해 주세요.");
        }
        return cleaned;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    public void addTraveler(Traveler traveler) {
        traveler.attachTo(this);
        travelers.add(traveler);
    }

    /** 허니문: 정확히 2인 신청마다 독립적으로 출발을 확정한다. */
    public void confirmHoneymoonDeparture() {
        if (!product.isHoneymoon()) {
            throw new IllegalStateException("허니문 신청만 신청별로 출발을 확정한다.");
        }
        departureStatus = DepartureStatus.CONFIRMED;
    }

    public TravelConfiguration getConfiguration() {
        return new TravelConfiguration(hotelLevel.name(), transport.name(), meal.name(),
                TravelConfiguration.normalizeDrinks(extraDrinks));
    }

    public Long getProductId() {
        return product.getProductId();
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

    public Long getApplicationId() {
        return applicationId;
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
