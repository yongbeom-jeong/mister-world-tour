package com.misterworldtour.travel;

/** 여행 테마와 테마별 기본 서비스·교통 (계획서 1.다). */
public enum Theme {
    HONEYMOON("허니문 낭만 투어", "2인 전용 로맨틱 룸 장식", TransportOption.LUXURY_CAR_2),
    FILIAL("효도 힐링 투어", "안마·지압 서비스", TransportOption.LUXURY_VAN_10),
    GOLF("골프 챌린지 투어", "골프 리조트 테마 연출", TransportOption.LUXURY_VAN_10),
    TREKKING("아웃도어 트레킹 투어", "트레킹·산악 테마 연출", TransportOption.LUXURY_VAN_10);

    private final String label;
    private final String defaultService;
    private final TransportOption defaultTransport;

    Theme(String label, String defaultService, TransportOption defaultTransport) {
        this.label = label;
        this.defaultService = defaultService;
        this.defaultTransport = defaultTransport;
    }

    public String getLabel() {
        return label;
    }

    /** 메뉴용 짧은 이름 ("허니문 낭만 투어" → "허니문 낭만"). */
    public String getShortLabel() {
        return label.replace(" 투어", "");
    }

    public String getDefaultService() {
        return defaultService;
    }

    public TransportOption getDefaultTransport() {
        return defaultTransport;
    }

    /** 허니문·효도: 그랜드 등급 이상만 (계획서 1.다). */
    public boolean isLevelRestricted() {
        return this == HONEYMOON || this == FILIAL;
    }

    /** 요청 값(문자열)을 테마로. 없거나 잘못된 값이면 null. */
    public static Theme parse(String value) {
        for (Theme t : values()) {
            if (t.name().equals(value)) {
                return t;
            }
        }
        return null;
    }
}
