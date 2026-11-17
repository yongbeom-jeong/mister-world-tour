package com.misterworldtour.members;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * 로그인 세션 (설계 4.1 LoginSession). 로그인 성공 시 MemberManager 가 만들고 사용자별 서버 세션에 보관한다.
 * 이후 요청에서 현재 로그인한 회원과 역할을 확인하는 데 쓴다. 화면에서는 {@code currentMember} 로 쓴다.
 *
 * <p>설계의 회원 고유번호·역할 외에 화면 표시용 아이디·성명을 함께 담는다 (엔티티를 세션에 넣지 않기 위해).
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

    /** 성명이 없으면 아이디. */
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
