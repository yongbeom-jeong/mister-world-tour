package com.misterworldtour.travel;

/** 교통. 리무진은 임시 옵션 (AGENTS.md 임시 결정). */
public enum TransportOption {
    LUXURY_CAR_2("2인 전용 고급차량"),
    LUXURY_VAN_10("10인 승합 고급차량"),
    LIMOUSINE("고급차량 + 공항 리무진 픽업");

    private final String label;

    TransportOption(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
