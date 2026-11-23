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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 직원이 등록한 여행 상품 (설계 5.1 TravelProduct).
 * 출발 상태는 허니문 외 테마만 쓰고, 허니문은 null(신청별 상태). 기념품은 상품이 아니라 테마에 연결된다.
 */
@Entity
@Table(name = "travel_product")
public class TravelProduct {

    // 입력 범위
    public static final int MAX_NAME = 100;
    public static final int MIN_PRICE = 10_000;
    public static final int MAX_PRICE = 50_000_000;
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long productId;

    @Column(name = "name", nullable = false, length = 100)
    private String productName;

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

    /** 여행 기간 (일 수). */
    @Column(name = "travel_days", nullable = false)
    private int travelPeriod;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    // 기본 구성 (설계 defaultConfiguration)
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

    /** 직원이 출발 처리한 시각 (이때 기념품 차감). 없으면 아직 출발 전. */
    @Column(name = "departed_at")
    private OffsetDateTime departedAt;

    /** 직원이 여행 완료 처리한 시각 (이때 완료 이력 생성). */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected TravelProduct() {
    }

    public TravelProduct(String productName, String destination, String description, Theme theme, TourLevel tourLevel,
                         int basePrice, int travelPeriod, LocalDate departureDate) {
        update(productName, destination, description, theme, tourLevel, basePrice, travelPeriod, departureDate);
    }

    /** 직원 입력값을 넣고 기본 구성·출발 상태를 다시 정한다. */
    public void update(String productName, String destination, String description, Theme theme, TourLevel tourLevel,
                       int basePrice, int travelPeriod, LocalDate departureDate) {
        this.productName = productName;
        this.destination = destination == null ? "" : destination;
        this.description = description == null ? "" : description;
        this.theme = theme;
        this.tourLevel = tourLevel;
        this.basePrice = basePrice;
        this.travelPeriod = travelPeriod;
        this.departureDate = departureDate;
        this.departureStatus = null;
        if (theme != null && tourLevel != null) {
            setDefaultConfiguration(new TravelConfiguration(theme, tourLevel));
        }
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** 상품 정보 검사 (설계 ValidateProduct, UC6·UC7). 문제 항목별 안내, 비어 있으면 유효. */
    public Map<String, String> validateProduct(LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (productName == null || productName.isBlank()) {
            errors.put("name", "필수 항목입니다.");
        } else if (productName.length() > MAX_NAME) {
            errors.put("name", MAX_NAME + "자 이하로 입력해 주세요.");
        }
        if (destination.isBlank()) {
            errors.put("destination", "필수 항목입니다.");
        } else if (destination.length() > 100) {
            errors.put("destination", "100자 이하로 입력해 주세요.");
        }
        if (theme == null) {
            errors.put("theme", "테마를 고르세요.");
        }
        if (tourLevel == null) {
            errors.put("tour_level", "등급을 고르세요.");
        }
        if (basePrice < MIN_PRICE || basePrice > MAX_PRICE) {
            errors.put("base_price", "1인 기본 가격은 %,d원 이상 %,d원 이하로 입력해 주세요.".formatted(MIN_PRICE, MAX_PRICE));
        }
        if (travelPeriod < MIN_DAYS || travelPeriod > MAX_DAYS) {
            errors.put("travel_days", "여행 기간은 %d~%d일로 입력해 주세요.".formatted(MIN_DAYS, MAX_DAYS));
        }
        if (departureDate == null) {
            errors.put("departure_date", "출발일을 고르세요.");
        } else if (today != null && !departureDate.isAfter(today)) {
            errors.put("departure_date", "출발일은 내일 이후로 지정해 주세요.");
        }
        if (theme != null && tourLevel != null && theme.isLevelRestricted()
                && !TravelRules.ALLOWED_LEVELS_FOR_RESTRICTED.contains(tourLevel)) {
            errors.put("tour_level", "허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만 가능합니다.");
        }
        return errors;
    }

    /** 허니문은 신청마다 정확히 2명, 그 외는 상품 전체 정원 10명. 문제가 있으면 안내 문구, 없으면 null. */
    public String validateApplicationCapacity(int currentTravelerCount, int requestedTravelerCount) {
        if (isHoneymoon()) {
            return requestedTravelerCount != TravelRules.HONEYMOON_PARTY_SIZE
                    ? "허니문 상품은 신청 고객 1명과 동반 1명, 정확히 2명이어야 합니다." : null;
        }
        if (requestedTravelerCount < 1) {
            return "여행자를 1명 이상 입력해 주세요.";
        }
        if (currentTravelerCount + requestedTravelerCount > TravelRules.GROUP_CAPACITY) {
            return "정원 %d명을 넘습니다. (현재 %d명, 남은 자리 %d명)".formatted(TravelRules.GROUP_CAPACITY,
                    currentTravelerCount, TravelRules.GROUP_CAPACITY - currentTravelerCount);
        }
        return null;
    }

    /** 허니문 외 상품: 실제 여행자가 3명 이상이면 공동 출발을 확정한다. 확정 후에는 유지한다. */
    public DepartureStatus updateDepartureStatus(int totalTravelerCount) {
        if (isHoneymoon()) {
            return null;
        }
        if (departureStatus != DepartureStatus.CONFIRMED && totalTravelerCount >= TravelRules.GROUP_MIN_DEPARTURE) {
            departureStatus = DepartureStatus.CONFIRMED;
        }
        return departureStatus;
    }

    public TravelConfiguration getDefaultConfiguration() {
        return TravelConfiguration.defaultOf(this);
    }

    /** 기본 구성을 정한다. 허니문 외 상품은 출발 상태를 미확정으로 시작한다. */
    public void setDefaultConfiguration(TravelConfiguration configuration) {
        themeService = theme.getDefaultService();
        hotelLevel = HotelLevel.valueOf(configuration.hotelLevel());
        transport = TransportOption.valueOf(configuration.transport());
        meal = MealOption.valueOf(configuration.meal());
        if (isHoneymoon()) {
            departureStatus = null;
        } else if (departureStatus == null) {
            departureStatus = DepartureStatus.PENDING;
        }
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

    /** 직원 출발 처리. */
    public void markDeparted(OffsetDateTime at) {
        departedAt = at;
    }

    /** 직원 완료 처리. */
    public void markCompleted(OffsetDateTime at) {
        completedAt = at;
    }

    public boolean isDeparted() {
        return departedAt != null;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public OffsetDateTime getDepartedAt() {
        return departedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    /** 여행 마지막 날 (출발일이 1일째). */
    public LocalDate getTripEndDate() {
        return departureDate.plusDays(Math.max(travelPeriod, 1) - 1L);
    }

    /** 화면용 사진 주소. 올린 사진이 없으면 테마 기본 사진. */
    public String getPhotoUrl() {
        return photo(false);
    }

    /** 화면용 작은 사진 주소 (카드·목록, 800px). */
    public String getPhotoSmUrl() {
        return photo(true);
    }

    private String photo(boolean small) {
        if (image != null && !image.isBlank()) {
            return "/media/" + image;
        }
        return "/img/photos/" + (small ? "sm/" : "") + "theme_" + theme.name().toLowerCase(java.util.Locale.ROOT) + ".jpg";
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
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

    public int getTravelPeriod() {
        return travelPeriod;
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
        return "[" + theme.getLabel() + "/" + tourLevel.getLabel() + "] " + productName;
    }
}
