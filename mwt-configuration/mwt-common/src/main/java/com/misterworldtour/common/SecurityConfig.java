package com.misterworldtour.common;

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

/**
 * 로그인·로그아웃과 접근 제한.
 *
 * <p>로그인만 필요한지는 여기서, 고객/직원 역할은 각 컨트롤러의
 * {@code @CustomerRequired} / {@code @StaffRoleRequired} 로 검사한다 .
 *
 * <p>로그인 성공·실패 처리는 User Interface 가 빈으로 제공하고(ui.LoginHandlers), 여기서는 스프링 인터페이스 타입으로만
 * 받는다. 그래서 common 은 ui 를 참조하지 않는다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * JSON 으로 응답하는 직원 기능 (기념품 입고). 로그인 만료·권한 없음을
     * 로그인 화면 이동 대신 JSON 403 PERMISSION_DENIED 로 알린다 (화면 스크립트가 '권한 거부'를 '반영 불명'과 구분).
     */
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
                        // 로그인 필요: JSON 직원 기능은 JSON 403, 나머지는 로그인 화면으로
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
