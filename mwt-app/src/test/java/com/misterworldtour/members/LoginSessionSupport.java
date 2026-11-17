package com.misterworldtour.members;


import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

/** 테스트용 회원 만들기. MockMvc 요청에 {@link #login} 으로 로그인 세션을 붙인다. */
@Component
public class LoginSessionSupport {

    public static final String PASSWORD = "Travel-2026!x";

    private final MemberDataAccessAPI dataAccess;

    LoginSessionSupport(MemberDataAccessAPI dataAccess) {
        this.dataAccess = dataAccess;
    }

    public LoginSession create(String account, Role role) {
        return create(account, role, "이름" + account, "010-1234-5678");
    }

    public LoginSession create(String account, Role role, String name, String contact) {
        Member m = dataAccess.saveMember(new Member(account, Member.hashPassword(PASSWORD), name, "주소", contact, role));
        return new LoginSession(m.getMemberId(), m.getRole(), m.getAccount(), m.getName());
    }

    /** 이 로그인 세션으로 보낸 요청 (Spring Security 인증 주체가 LoginSession). */
    public static RequestPostProcessor login(LoginSession session) {
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, List.of(new SimpleGrantedAuthority(session.getRole().authority()))));
    }
}
