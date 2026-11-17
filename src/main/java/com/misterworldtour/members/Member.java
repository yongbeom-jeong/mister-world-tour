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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 고객·직원 공통 회원 정보 (설계 4.1 Member).
 *
 * <p>회원 고유번호는 저장 후 부여되고, 역할(role)로 고객과 직원을 구분한다.
 * 아이디는 대소문자를 무시하고 중복 금지: 소문자로 바꾼 {@code accountKey} 에 DB 유일 제약을 건다.
 */
@Entity
@Table(name = "member")
public class Member {

    /** 비밀번호 해시 방식 (기본 bcrypt). 저장값 앞에 {bcrypt} 처럼 방식이 붙어 나중에 바꿔도 기존 비밀번호가 맞는다. */
    private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long memberId;

    @Column(name = "username", nullable = false, length = 20)
    private String account;

    @Column(name = "username_key", nullable = false, length = 20, unique = true)
    private String accountKey;

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
    private OffsetDateTime joinedAt;

    protected Member() {
    }

    public Member(String account, String passwordHash, String name, String address, String contact, Role role) {
        this.account = account;
        this.passwordHash = passwordHash;
        this.name = name;
        this.address = address == null ? "" : address;
        this.contact = contact;
        this.role = role;
    }

    /** 비밀번호 해시를 만든다 (MemberManager 가 가입 때 사용). */
    public static String hashPassword(String password) {
        return PASSWORD_ENCODER.encode(password);
    }

    /** 대소문자를 무시한 중복 검사용 아이디. */
    public static String accountKeyOf(String account) {
        return account == null ? "" : account.toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void fillDerived() {
        accountKey = accountKeyOf(account);
        if (joinedAt == null) {
            joinedAt = OffsetDateTime.now();
        }
    }

    /** 입력한 비밀번호가 저장된 해시와 맞는지. */
    public boolean checkPassword(String password) {
        return password != null && passwordHash != null && PASSWORD_ENCODER.matches(password, passwordHash);
    }

    /** 없는 아이디로 로그인할 때도 같은 시간이 걸리게 한 번 비교한다 (아이디 존재 여부 노출 방지). */
    static void checkDummyPassword(String password) {
        PASSWORD_ENCODER.matches(password == null ? "" : password, DUMMY_HASH);
    }

    private static final String DUMMY_HASH = hashPassword("dummy-password-for-timing");

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    /** 여행사 직원 여부. */
    public boolean isStaffMember() {
        return role == Role.STAFF;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getAccount() {
        return account;
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

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }

    @Override
    public String toString() {
        return name + "(" + account + ")";
    }
}
