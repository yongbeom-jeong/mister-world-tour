package com.misterworldtour.travel;

/** 식사 (낮은 것부터 높은 순서). */
public enum MealOption {
    LUNCHBOX("도시락"),
    LOCAL("현지식 레스토랑 식사"),
    FINE("고급 레스토랑 스테이크·샴페인");

    private final String label;

    MealOption(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
