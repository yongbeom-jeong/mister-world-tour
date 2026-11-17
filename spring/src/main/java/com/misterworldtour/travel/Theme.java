package com.misterworldtour.travel;

/** 여행 테마 (설계 01장 5.1). */
public enum Theme {
    HONEYMOON("허니문 낭만 투어"),
    FILIAL("효도 힐링 투어"),
    GOLF("골프 챌린지 투어"),
    TREKKING("아웃도어 트레킹 투어");

    private final String label;

    Theme(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 메뉴용 짧은 이름 ("허니문 낭만 투어" → "허니문 낭만"). */
    public String getShortLabel() {
        return label.replace(" 투어", "");
    }
}
