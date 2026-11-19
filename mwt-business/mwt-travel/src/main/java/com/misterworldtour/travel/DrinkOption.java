package com.misterworldtour.travel;

/** 식사에 추가하는 음료 (1인 1세트). */
public enum DrinkOption {
    WINE("와인 페어링"),
    BEER("수제맥주 세트"),
    CHAMPAGNE("샴페인"),
    COFFEE("스페셜티 커피"); // 과제 원문 "샴페인, 커피 추가"

    private final String label;

    DrinkOption(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
