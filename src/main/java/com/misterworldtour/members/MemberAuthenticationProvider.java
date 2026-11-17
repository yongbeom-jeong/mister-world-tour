package com.misterworldtour.members;

import com.misterworldtour.members.MemberManagementAPI.LoginInfo;
import com.misterworldtour.members.MemberManagementAPI.LoginResult;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 로그인 폼 제출을 {@link MemberManager#logIn} 으로 처리한다 (UC2).
 * 성공하면 LoginSession 을 인증 주체로 담아 Spring Security 가 사용자별 서버 세션에 보관한다.
 */
@Component
public class MemberAuthenticationProvider implements AuthenticationProvider {

    private final MemberManagementAPI memberManager;

    MemberAuthenticationProvider(MemberManagementAPI memberManager) {
        this.memberManager = memberManager;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String account = authentication.getName();
        Object credentials = authentication.getCredentials();
        LoginResult result = memberManager.logIn(new LoginInfo(account, credentials == null ? "" : credentials.toString()));
        if (!result.isSuccess()) {
            throw new BadCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        LoginSession session = result.loginSession();
        return UsernamePasswordAuthenticationToken.authenticated(session, null,
                List.of(new SimpleGrantedAuthority(session.getRole().authority())));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
