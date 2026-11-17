package com.misterworldtour.travel;

/** 투어 등급과 등급별 기본 호텔·식사 (설계 01장 5.1, Django 판 LEVEL_DEFAULTS). */
public enum TourLevel {
    CLASSIC("클래식", HotelLevel.STAR3, MealOption.LUNCHBOX),
    GRAND("그랜드", HotelLevel.STAR4, MealOption.LOCAL),
    PREMIUM("프리미엄", HotelLevel.STAR5, MealOption.FINE);

    private final String label;
    private final HotelLevel defaultHotel;
    private final MealOption defaultMeal;

    TourLevel(String label, HotelLevel defaultHotel, MealOption defaultMeal) {
        this.label = label;
        this.defaultHotel = defaultHotel;
        this.defaultMeal = defaultMeal;
    }

    public String getLabel() {
        return label;
    }

    public HotelLevel getDefaultHotel() {
        return defaultHotel;
    }

    public MealOption getDefaultMeal() {
        return defaultMeal;
    }

    /** 요청 값(문자열)을 등급으로. 없거나 잘못된 값이면 null. */
    public static TourLevel parse(String value) {
        for (TourLevel lv : values()) {
            if (lv.name().equals(value)) {
                return lv;
            }
        }
        return null;
    }
}
