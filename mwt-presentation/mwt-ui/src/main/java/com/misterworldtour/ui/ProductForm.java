package com.misterworldtour.ui;

import com.misterworldtour.travel.HotelLevel;
import com.misterworldtour.travel.MealOption;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TransportOption;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelManagementAPI.ProductInput;
import com.misterworldtour.travel.TravelProduct;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 상품 등록·수정 입력 값 (TourProductAdminView). {@link #validate} 는 형식만 보고,
 * 범위·기본 구성 규칙은 validateProduct · validateConfiguration 이 검사한다.
 */
public class ProductForm {

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
        f.name = p.getProductName();
        f.destination = p.getDestination();
        f.description = p.getDescription();
        f.theme = p.getTheme().name();
        f.tourLevel = p.getTourLevel().name();
        f.basePrice = String.valueOf(p.getBasePrice());
        f.travelDays = String.valueOf(p.getTravelPeriod());
        f.departureDate = p.getDepartureDate().toString();
        f.hotelLevel = p.getHotelLevel().name();
        f.meal = p.getMeal().name();
        f.transport = p.getTransport().name();
        return f;
    }

    private static String s(String v) {
        return v == null ? "" : v.strip();
    }

    /** 항목별 형식 오류. 비어 있어야 input() 을 쓸 수 있다. */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (Theme.parse(s(theme)) == null) {
            errors.put("theme", "테마를 고르세요.");
        }
        if (TourLevel.parse(s(tourLevel)) == null) {
            errors.put("tour_level", "등급을 고르세요.");
        }
        if (parseInt(basePrice) == null) {
            errors.put("base_price", "1인 기본 가격을 숫자로 입력해 주세요.");
        }
        if (parseInt(travelDays) == null) {
            errors.put("travel_days", "여행 기간을 숫자로 입력해 주세요.");
        }
        if (parseDate(departureDate) == null) {
            errors.put("departure_date", "출발일을 고르세요.");
        }
        if (!s(hotelLevel).isEmpty() && TravelConfiguration.parse(HotelLevel.class, s(hotelLevel)) == null) {
            errors.put("hotel_level", "올바른 값을 고르세요.");
        }
        if (!s(meal).isEmpty() && TravelConfiguration.parse(MealOption.class, s(meal)) == null) {
            errors.put("meal", "올바른 값을 고르세요.");
        }
        if (!s(transport).isEmpty() && TravelConfiguration.parse(TransportOption.class, s(transport)) == null) {
            errors.put("transport", "올바른 값을 고르세요.");
        }
        return errors;
    }

    /** 비운 기본 구성은 null (테마·등급 기본값 사용). */
    public ProductInput input() {
        return new ProductInput(s(name), s(destination), description == null ? "" : description.strip(),
                Theme.parse(s(theme)), TourLevel.parse(s(tourLevel)), parseInt(basePrice), parseInt(travelDays),
                parseDate(departureDate), TravelConfiguration.parse(HotelLevel.class, s(hotelLevel)),
                TravelConfiguration.parse(MealOption.class, s(meal)),
                TravelConfiguration.parse(TransportOption.class, s(transport)));
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
