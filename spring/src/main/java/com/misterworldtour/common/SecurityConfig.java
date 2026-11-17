package com.misterworldtour.common;

import com.misterworldtour.members.LoginHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 로그인·로그아웃과 접근 제한.
 *
 * <p>로그인만 필요한지는 여기서, 고객/직원 역할은 각 컨트롤러의
 * {@code @CustomerRequired} / {@code @StaffRoleRequired} 로 검사한다 (Django 판 데코레이터와 같은 자리).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/", "/credits/", "/members/**", "/error").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/img/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/members/login/")
                        .loginProcessingUrl("/members/login/")
                        .successHandler(new LoginHandlers.Success())
                        .failureHandler(new LoginHandlers.Failure())
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/members/logout/")
                        .logoutSuccessUrl("/"));
        return http.build();
    }

    /** 기본은 bcrypt. 저장값 앞에 {bcrypt} 처럼 방식이 붙어 나중에 바꿔도 기존 비밀번호가 계속 맞는다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
