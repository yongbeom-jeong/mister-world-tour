package com.misterworldtour.travel;

/** 호텔 등급 (낮은 것부터 높은 순서). */
public enum HotelLevel {
    STAR3("3등급 호텔"),
    STAR4("4등급 호텔"),
    STAR5("5등급 호텔");

    private final String label;

    HotelLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
