package com.misterworldtour.ui;

import com.misterworldtour.members.LoginSession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * 모든 화면 레이아웃에 쓰는 값(로그인 회원, 메뉴 테마·등급, 현재 탭, 이력 팝업 표시 UC2 (4))을 넣는다.
 * redirect 응답에서는 팝업 표시를 꺼내지 않는다.
 */
@Component
public class LayoutModelInterceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView mv) {
        String view = mv == null ? null : mv.getViewName();
        if (mv == null || mv.getView() instanceof RedirectView || (view != null && view.startsWith("redirect:"))
                || (view != null && view.contains("/_"))) { // redirect·화면 조각(_로 시작)은 제외
            return;
        }
        LoginSession member = currentMember();
        mv.addObject("currentMember", member);
        mv.addObject("siteThemes", Display.themes());
        mv.addObject("siteLevels", Display.levels());
        mv.addObject("requestPath", request.getRequestURI().substring(request.getContextPath().length()));
        mv.addObject("showHistoryPopup", popHistoryPopup(request, member));
    }

    static LoginSession currentMember() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginSession p ? p : null;
    }

    private static boolean popHistoryPopup(HttpServletRequest request, LoginSession member) {
        if (member == null || !member.isCustomer()) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(LoginHandlers.SHOW_HISTORY_POPUP) == null) {
            return false;
        }
        session.removeAttribute(LoginHandlers.SHOW_HISTORY_POPUP);
        return true;
    }
}
