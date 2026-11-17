package com.misterworldtour.travel;

import com.misterworldtour.travel.TravelService.ProductInput;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UC6·UC7 상품 입력 (Django 판 travel/forms.py ProductForm). 화면 값은 문자열로 받고 {@link #validate} 로 검사한다.
 * 기본 구성(hotelLevel·meal·transport)은 테마·등급 기본값을 보여 주고 직원이 확인한 뒤 바꿀 수 있다. 비우면 기본값.
 */
public class ProductForm {

    // 임시 정책: 입력 범위 (설계 미정)
    public static final int MIN_PRICE = 10_000;
    public static final int MAX_PRICE = 50_000_000;
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 30;

    private String name = "";
    private String destination = "";
    private String description = "";
    private String theme = "";
    private String tourLevel = "";
    private String basePrice = "";
    private String travelDays = "";
    private String departureDate = "";
    private String hotelLevel = "";
    private String meal = "";
    private String transport = "";

    public static ProductForm of(TravelProduct p) {
        ProductForm f = new ProductForm();
        f.name = p.getName();
        f.destination = p.getDestination();
        f.description = p.getDescription();
        f.theme = p.getTheme().name();
        f.tourLevel = p.getTourLevel().name();
        f.basePrice = String.valueOf(p.getBasePrice());
        f.travelDays = String.valueOf(p.getTravelDays());
        f.departureDate = p.getDepartureDate().toString();
        f.hotelLevel = p.getHotelLevel().name();
        f.meal = p.getMeal().name();
        f.transport = p.getTransport().name();
        return f;
    }

    private static String s(String v) {
        return v == null ? "" : v.strip();
    }

    /** 항목별 오류. 비어 있으면 통과하며 input() 으로 값을 꺼낼 수 있다. */
    public Map<String, String> validate(LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (s(name).isEmpty()) {
            errors.put("name", "필수 항목입니다.");
        } else if (s(name).length() > 100) {
            errors.put("name", "100자 이하로 입력해 주세요.");
        }
        if (s(destination).isEmpty()) { // 고객 화면 표시에 쓰므로 필수 (임시 정책)
            errors.put("destination", "필수 항목입니다.");
        } else if (s(destination).length() > 100) {
            errors.put("destination", "100자 이하로 입력해 주세요.");
        }
        Theme t = Theme.parse(s(theme));
        if (t == null) {
            errors.put("theme", "테마를 고르세요.");
        }
        TourLevel lv = TourLevel.parse(s(tourLevel));
        if (lv == null) {
            errors.put("tour_level", "등급을 고르세요.");
        }
        Integer price = parseInt(basePrice);
        if (price == null) {
            errors.put("base_price", "1인 기본 가격을 숫자로 입력해 주세요.");
        } else if (price < MIN_PRICE || price > MAX_PRICE) {
            errors.put("base_price", "1인 기본 가격은 %,d원 이상 %,d원 이하로 입력해 주세요.".formatted(MIN_PRICE, MAX_PRICE));
        }
        Integer days = parseInt(travelDays);
        if (days == null) {
            errors.put("travel_days", "여행 기간을 숫자로 입력해 주세요.");
        } else if (days < MIN_DAYS || days > MAX_DAYS) {
            errors.put("travel_days", "여행 기간은 %d~%d일로 입력해 주세요.".formatted(MIN_DAYS, MAX_DAYS));
        }
        LocalDate date = parseDate(departureDate);
        if (date == null) {
            errors.put("departure_date", "출발일을 고르세요.");
        } else if (!date.isAfter(today)) {
            errors.put("departure_date", "출발일은 내일 이후로 지정해 주세요.");
        }
        HotelLevel hotel = ConfigurationRules.parse(HotelLevel.class, s(hotelLevel));
        MealOption m = ConfigurationRules.parse(MealOption.class, s(meal));
        TransportOption tr = ConfigurationRules.parse(TransportOption.class, s(transport));
        if (!s(hotelLevel).isEmpty() && hotel == null) {
            errors.put("hotel_level", "올바른 값을 고르세요.");
        }
        if (!s(meal).isEmpty() && m == null) {
            errors.put("meal", "올바른 값을 고르세요.");
        }
        if (!s(transport).isEmpty() && tr == null) {
            errors.put("transport", "올바른 값을 고르세요.");
        }
        if (t != null && lv != null) {
            errors.putAll(ConfigurationRules.baseConfigurationErrors(t, lv, hotel, m, tr));
            if (t.isLevelRestricted() && !TravelRules.ALLOWED_LEVELS_FOR_RESTRICTED.contains(lv)) {
                errors.put("tour_level", "허니문·효도 상품의 초기 등급은 그랜드 또는 프리미엄만 가능합니다.");
            }
        }
        return errors;
    }

    /** 검사를 통과한 값. 비운 기본 구성은 null (서비스가 테마·등급 기본값을 쓴다). */
    public ProductInput input() {
        return new ProductInput(s(name), s(destination), description == null ? "" : description.strip(),
                Theme.parse(s(theme)), TourLevel.parse(s(tourLevel)), parseInt(basePrice), parseInt(travelDays),
                parseDate(departureDate), ConfigurationRules.parse(HotelLevel.class, s(hotelLevel)),
                ConfigurationRules.parse(MealOption.class, s(meal)),
                ConfigurationRules.parse(TransportOption.class, s(transport)));
    }

    private static Integer parseInt(String v) {
        String t = s(v).replace(",", "");
        if (!t.matches("\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(t);
    }

    private static LocalDate parseDate(String v) {
        try {
            return s(v).isEmpty() ? null : LocalDate.parse(s(v));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getTourLevel() {
        return tourLevel;
    }

    public void setTourLevel(String tourLevel) {
        this.tourLevel = tourLevel;
    }

    public String getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(String basePrice) {
        this.basePrice = basePrice;
    }

    public String getTravelDays() {
        return travelDays;
    }

    public void setTravelDays(String travelDays) {
        this.travelDays = travelDays;
    }

    public String getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(String departureDate) {
        this.departureDate = departureDate;
    }

    public String getHotelLevel() {
        return hotelLevel;
    }

    public void setHotelLevel(String hotelLevel) {
        this.hotelLevel = hotelLevel;
    }

    public String getMeal() {
        return meal;
    }

    public void setMeal(String meal) {
        this.meal = meal;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }
}
