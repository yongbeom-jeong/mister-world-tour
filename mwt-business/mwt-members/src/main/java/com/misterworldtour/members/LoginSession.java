package com.misterworldtour.members;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * 로그인 세션 (설계 4.1 LoginSession). Spring Security 인증 주체로 서버 세션에 보관한다.
 * 엔티티 대신 화면 표시용 아이디·성명을 함께 담는다. 화면에서는 {@code currentMember}.
 */
public class LoginSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long memberId;
    private final Role role;
    private final String account;
    private final String name;

    public LoginSession(Long memberId, Role role, String account, String name) {
        this.memberId = memberId;
        this.role = role;
        this.account = account;
        this.name = name;
    }

    /** 지금 요청의 로그인 세션. 로그인하지 않았으면 empty. */
    public static Optional<LoginSession> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginSession s ? Optional.of(s) : Optional.empty();
    }

    public Long getMemberId() {
        return memberId;
    }

    public Role getRole() {
        return role;
    }

    public String getAccount() {
        return account;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return name == null || name.isBlank() ? account : name;
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    public boolean isStaffMember() {
        return role == Role.STAFF;
    }

    @Override
    public String toString() {
        return account;
    }
}
