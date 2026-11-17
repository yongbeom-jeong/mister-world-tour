package com.misterworldtour.members;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** UC2 로그인: 아이디로 회원을 찾아 Spring Security 에 넘긴다. 비밀번호 비교는 Spring Security 가 한다. */
@Service
class MemberUserDetailsService implements UserDetailsService {

    private final MemberService memberService;

    MemberUserDetailsService(MemberService memberService) {
        this.memberService = memberService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return memberService.findByUsername(username)
                .map(MemberPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("없는 아이디"));
    }
}
