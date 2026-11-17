package com.misterworldtour.members;

/** 회원 역할. 직원 여부는 이 값으로만 판단한다 (Django 판의 is_staff 관리자 권한과는 무관). */
public enum Role {
    CUSTOMER("고객"),
    STAFF("직원");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Spring Security 권한 이름 (hasRole("CUSTOMER") 와 대응). */
    public String authority() {
        return "ROLE_" + name();
    }
}
