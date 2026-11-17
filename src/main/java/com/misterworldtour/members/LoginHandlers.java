package com.misterworldtour.members;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.Serializable;

/** UC2 로그인 성공·실패 처리. */
public final class LoginHandlers {

    /** 고객 로그인 직후 처음 그리는 화면에서 이력 팝업을 한 번 연다 (UC2 (4)). */
    public static final String SHOW_HISTORY_POPUP = "show_history_popup";
    static final String LOGIN_FAILURE = "login_failure";

    /** 로그인 실패 내용. 로그인 화면이 한 번 꺼내 쓴다. */
    public record LoginFailure(boolean blank, String username, String next) implements Serializable {
    }

    private LoginHandlers() {
    }

    /** 고객이면 이력 팝업 표시를 남기고, next(로그인 전 보던 화면) → 저장된 요청 → 홈 순으로 이동한다. */
    public static class Success extends SavedRequestAwareAuthenticationSuccessHandler {

        public Success() {
            setDefaultTargetUrl("/");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException, jakarta.servlet.ServletException {
            if (authentication.getPrincipal() instanceof MemberPrincipal p && p.isCustomer()) {
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

    /** 빈 칸이면 '모두 입력', 아니면 '아이디 또는 비밀번호가 올바르지 않음'으로 안내한다 (입력한 아이디는 다시 채움). */
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

    /** 같은 사이트 안의 경로만 허용한다 (외부 주소로 보내는 열린 리다이렉트 방지). */
    static boolean isSafeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\");
    }
}
