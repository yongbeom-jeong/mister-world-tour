package com.misterworldtour.members;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 테스트용 회원 만들기. MockMvc 의 user(...) 에 넘길 로그인 정보를 돌려준다. */
@Component
public class MemberPrincipalSupport {

    public static final String PASSWORD = "Travel-2026!x";

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;

    MemberPrincipalSupport(MemberRepository members, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
    }

    public MemberPrincipal create(String username, Role role) {
        return create(username, role, "이름" + username, "010-1234-5678");
    }

    public MemberPrincipal create(String username, Role role, String name, String contact) {
        Member m = members.saveAndFlush(new Member(username, passwordEncoder.encode(PASSWORD), name, "주소", contact, role));
        return new MemberPrincipal(m);
    }
}
