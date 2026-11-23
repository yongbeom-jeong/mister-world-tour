package com.misterworldtour.travel;

/** 식사에 추가하는 음료. 신청 전체 기준 수량으로 고른다 (예: 커피 2포트, 샴페인 1병). */
public enum DrinkOption {
    WINE("와인 페어링", "병"),
    BEER("수제맥주 세트", "세트"),
    CHAMPAGNE("샴페인", "병"),
    COFFEE("스페셜티 커피", "포트"); // 과제 원문 "샴페인, 커피 추가"

    private final String label;
    private final String unit;

    DrinkOption(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String getLabel() {
        return label;
    }

    /** 수량 단위 (병·포트·세트). */
    public String getUnit() {
        return unit;
    }
}
