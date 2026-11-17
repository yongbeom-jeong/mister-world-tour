package com.misterworldtour.travel;

public enum DepartureStatus {
    PENDING("미확정"),
    CONFIRMED("출발 확정");

    private final String label;

    DepartureStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
