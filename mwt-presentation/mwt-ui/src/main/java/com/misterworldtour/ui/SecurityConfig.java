package com.misterworldtour.ui;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 로그인·로그아웃과 접근 제한. 역할 검사는 {@code @CustomerRequired} / {@code @StaffRoleRequired} 로 한다. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 기념품 입고 JSON 요청: 로그인 만료·권한 없음을 화면 이동 대신 JSON 403 PERMISSION_DENIED 로 응답. */
    static final Set<String> STAFF_JSON_PATHS = Set.of(
            "/souvenir/staff/stock/list/", "/souvenir/staff/stock/prepare/", "/souvenir/staff/stock/record/");

    static final String PERMISSION_DENIED_JSON =
            "{\"ok\":false,\"status\":\"PERMISSION_DENIED\",\"message\":\"로그인이 만료되었거나 권한이 없습니다. 다시 로그인해 주세요.\"}";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationSuccessHandler loginSuccess,
                                           AuthenticationFailureHandler loginFailure) throws Exception {
        RequestMatcher staffJson = request -> STAFF_JSON_PATHS.contains(pathOf(request));
        LoginUrlAuthenticationEntryPoint loginPage = new LoginUrlAuthenticationEntryPoint("/members/login/");
        http
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/", "/credits/", "/members/**", "/error").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/img/**", "/media/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/members/login/")
                        .loginProcessingUrl("/members/login/")
                        .successHandler(loginSuccess)
                        .failureHandler(loginFailure)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/members/logout/")
                        .logoutSuccessUrl("/"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            if (staffJson.matches(req)) {
                                permissionDenied(res);
                            } else {
                                loginPage.commence(req, res, e);
                            }
                        })
                        .defaultAccessDeniedHandlerFor((req, res, e) -> permissionDenied(res), staffJson));
        return http.build();
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static void permissionDenied(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(PERMISSION_DENIED_JSON);
    }
}
