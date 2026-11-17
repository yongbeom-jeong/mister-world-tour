package com.misterworldtour.members;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 고객·직원 공통 회원 (설계 4장 Member).
 *
 * <p>아이디는 대소문자를 무시하고 중복 금지: 소문자로 바꾼 {@code usernameKey} 에 DB 유일 제약을 건다
 * (폼 검사 이후 동시 가입도 DB 에서 막는다).
 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String username;

    @Column(name = "username_key", nullable = false, length = 20, unique = true)
    private String usernameKey;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 200)
    private String address = "";

    @Column(nullable = false, length = 20)
    private String contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.CUSTOMER;

    @Column(name = "date_joined", nullable = false)
    private OffsetDateTime dateJoined;

    protected Member() {
    }

    public Member(String username, String passwordHash, String name, String address, String contact, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.name = name;
        this.address = address == null ? "" : address;
        this.contact = contact;
        this.role = role;
    }

    public static String keyOf(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void fillDerived() {
        usernameKey = keyOf(username);
        if (dateJoined == null) {
            dateJoined = OffsetDateTime.now();
        }
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    /** 여행사 직원 여부. */
    public boolean isStaffMember() {
        return role == Role.STAFF;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getContact() {
        return contact;
    }

    public Role getRole() {
        return role;
    }

    public OffsetDateTime getDateJoined() {
        return dateJoined;
    }

    @Override
    public String toString() {
        return name + "(" + username + ")";
    }
}
