package com.misterworldtour.travel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 직원이 등록한 여행 상품과 기본 구성, 공동 출발 상태 (설계 05장 TravelProduct).
 *
 * <p>기념품은 상품이 아니라 테마에 연결된다. 출발 상태는 허니문 외 테마만 쓰고, 허니문은 null(신청별 상태).
 */
@Entity
@Table(name = "travel_product")
public class TravelProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 여행지·소개: 설계에 없는 항목. 화면 표시용으로 임의 추가. */
    @Column(nullable = false, length = 100)
    private String destination = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    /** 대표 사진 경로 (media 폴더 기준, 예: products/2026/11/abc.jpg). 없으면 테마 기본 사진. */
    @Column(length = 200)
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Theme theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "tour_level", nullable = false, length = 10)
    private TourLevel tourLevel;

    @Column(name = "base_price", nullable = false)
    private int basePrice;

    @Column(name = "travel_days", nullable = false)
    private int travelDays;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "theme_service", nullable = false, length = 100)
    private String themeService;

    @Enumerated(EnumType.STRING)
    @Column(name = "hotel_level", nullable = false, length = 10)
    private HotelLevel hotelLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransportOption transport;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MealOption meal;

    @Enumerated(EnumType.STRING)
    @Column(name = "departure_status", length = 10)
    private DepartureStatus departureStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected TravelProduct() {
    }

    public TravelProduct(String name, String destination, String description, Theme theme, TourLevel tourLevel,
                         int basePrice, int travelDays, LocalDate departureDate) {
        update(name, destination, description, theme, tourLevel, basePrice, travelDays, departureDate);
    }

    /** 직원 입력값을 넣고 기본 구성·출발 상태를 다시 정한다 (신청이 없는 상품만 저장되므로 안전). */
    public void update(String name, String destination, String description, Theme theme, TourLevel tourLevel,
                       int basePrice, int travelDays, LocalDate departureDate) {
        this.name = name;
        this.destination = destination == null ? "" : destination;
        this.description = description == null ? "" : description;
        this.theme = theme;
        this.tourLevel = tourLevel;
        this.basePrice = basePrice;
        this.travelDays = travelDays;
        this.departureDate = departureDate;
        this.departureStatus = null;
        applyDefaultConfiguration();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** 테마·등급으로 기본 구성을 자동 결정한다. 등록·수정 시 호출. */
    public void applyDefaultConfiguration() {
        themeService = theme.getDefaultService();
        transport = theme.getDefaultTransport();
        hotelLevel = tourLevel.getDefaultHotel();
        meal = tourLevel.getDefaultMeal();
        if (isHoneymoon()) {
            departureStatus = null;
        } else if (departureStatus == null) {
            departureStatus = DepartureStatus.PENDING;
        }
    }

    /** 직원이 바꾼 기본 구성 (UC6 (3)). 검사는 호출하는 쪽에서 먼저 한다. */
    public void setBaseConfiguration(HotelLevel hotel, MealOption meal, TransportOption transport) {
        this.hotelLevel = hotel;
        this.meal = meal;
        this.transport = transport;
    }

    /** 허니문·효도 상품의 초기 등급 검사. 문제가 있으면 안내 문구, 없으면 null. */
    public String levelError() {
        if (theme.isLevelRestricted() && !TravelRules.ALLOWED_LEVELS_FOR_RESTRICTED.contains(tourLevel)) {
            return "허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만 가능합니다.";
        }
        return null;
    }

    public boolean isHoneymoon() {
        return theme == Theme.HONEYMOON;
    }

    public int getCapacity() {
        return isHoneymoon() ? TravelRules.HONEYMOON_PARTY_SIZE : TravelRules.GROUP_CAPACITY;
    }

    public boolean isConfirmed() {
        return departureStatus == DepartureStatus.CONFIRMED;
    }

    /** 정원 검사. 문제가 있으면 안내 문구, 없으면 null. */
    public String validateApplicationCapacity(int currentCount, int requestedCount) {
        if (isHoneymoon()) {
            return requestedCount != TravelRules.HONEYMOON_PARTY_SIZE
                    ? "허니문 상품은 신청 고객 1명과 동반 1명, 정확히 2명이어야 합니다." : null;
        }
        if (requestedCount < 1) {
            return "여행자를 1명 이상 입력해 주세요.";
        }
        if (currentCount + requestedCount > TravelRules.GROUP_CAPACITY) {
            return "정원 %d명을 넘습니다. (현재 %d명, 남은 자리 %d명)".formatted(TravelRules.GROUP_CAPACITY,
                    currentCount, TravelRules.GROUP_CAPACITY - currentCount);
        }
        return null;
    }

    /** 허니문 외 상품: 실제 여행자 3명 이상이면 확정. 확정 후에는 유지한다. */
    public DepartureStatus updateDepartureStatus(int totalTravelers) {
        if (isHoneymoon()) {
            return null;
        }
        if (departureStatus != DepartureStatus.CONFIRMED && totalTravelers >= TravelRules.GROUP_MIN_DEPARTURE) {
            departureStatus = DepartureStatus.CONFIRMED;
        }
        return departureStatus;
    }

    /** 여행 마지막 날 (출발일이 1일째). */
    public LocalDate getTripEndDate() {
        return departureDate.plusDays(Math.max(travelDays, 1) - 1L);
    }

    /** 화면용 사진 주소: 직원이 올린 사진, 없으면 테마 기본 사진. */
    public String getPhotoUrl() {
        return Display.productPhoto(this, false);
    }

    /** 화면용 작은 사진 주소 (카드·목록). */
    public String getPhotoSmUrl() {
        return Display.productPhoto(this, true);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDestination() {
        return destination;
    }

    public String getDescription() {
        return description;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Theme getTheme() {
        return theme;
    }

    public TourLevel getTourLevel() {
        return tourLevel;
    }

    public int getBasePrice() {
        return basePrice;
    }

    public int getTravelDays() {
        return travelDays;
    }

    public LocalDate getDepartureDate() {
        return departureDate;
    }

    public String getThemeService() {
        return themeService;
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

    public DepartureStatus getDepartureStatus() {
        return departureStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "[" + theme.getLabel() + "/" + tourLevel.getLabel() + "] " + name;
    }
}
