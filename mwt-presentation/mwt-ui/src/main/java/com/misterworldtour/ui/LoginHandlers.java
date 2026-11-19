package com.misterworldtour.ui;

import com.misterworldtour.members.LoginSession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.Serializable;

/** UC2 로그인 성공·실패 처리. */
public final class LoginHandlers {

    /** 로그인 직후 이력 팝업 한 번 (UC2 (4)). */
    public static final String SHOW_HISTORY_POPUP = "show_history_popup";
    static final String LOGIN_FAILURE = "login_failure";

    public record LoginFailure(boolean blank, String username, String next) implements Serializable {
    }

    private LoginHandlers() {
    }

    /** next → 저장된 요청 → 홈 순으로 이동. */
    @Component
    public static class Success extends SavedRequestAwareAuthenticationSuccessHandler {

        public Success() {
            setDefaultTargetUrl("/");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException, jakarta.servlet.ServletException {
            if (authentication.getPrincipal() instanceof LoginSession p && p.isCustomer()) {
                request.getSession().setAttribute(SHOW_HISTORY_POPUP, Boolean.TRUE);
            }
            String next = request.getParameter("next");
            if (isSafeNext(next)) {
                clearAuthenticationAttributes(request);
                getRedirectStrategy().sendRedirect(request, response, next);
                return;
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    @Component
    public static class Failure implements AuthenticationFailureHandler {

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException {
            String username = request.getParameter("username");
            String password = request.getParameter("password");
            String next = request.getParameter("next");
            boolean blank = username == null || username.isBlank() || password == null || password.isEmpty();
            HttpSession session = request.getSession();
            session.setAttribute(LOGIN_FAILURE, new LoginFailure(blank, username == null ? "" : username,
                    isSafeNext(next) ? next : ""));
            String target = UriComponentsBuilder.fromPath(request.getContextPath() + "/members/login/")
                    .queryParamIfPresent("next", java.util.Optional.ofNullable(isSafeNext(next) ? next : null))
                    .encode().toUriString();
            response.sendRedirect(target);
        }
    }

    /** 열린 리다이렉트 방지: 사이트 안 경로만. */
    static boolean isSafeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\");
    }
}
