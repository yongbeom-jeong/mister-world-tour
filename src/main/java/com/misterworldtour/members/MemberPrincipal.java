package com.misterworldtour.members;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * 로그인한 회원 (세션에 저장). 화면에서는 {@code currentMember} 로 쓴다.
 * 엔티티를 세션에 넣지 않으려고 화면·권한 검사에 필요한 값만 담는다.
 */
public class MemberPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;
    private final String name;
    private final Role role;
    private String passwordHash;

    MemberPrincipal(Member member) {
        this.id = member.getId();
        this.username = member.getUsername();
        this.name = member.getName();
        this.role = member.getRole();
        this.passwordHash = member.getPasswordHash();
    }

    public Long getId() {
        return id;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public String getName() {
        return name;
    }

    /** 성명이 없으면 아이디 (Django 판 user.name|default:user.username). */
    public String getDisplayName() {
        return name == null || name.isBlank() ? username : name;
    }

    public Role getRole() {
        return role;
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    public boolean isStaffMember() {
        return role == Role.STAFF;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
