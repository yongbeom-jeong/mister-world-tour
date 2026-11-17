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
 * 모든 화면(레이아웃)에서 쓰는 값을 넣는다 (MainView 의 역할별 메뉴 표시에 필요한 currentRole 등).
 * <ul>
 *   <li>currentMember: 로그인한 회원 (없으면 null)</li>
 *   <li>siteThemes·siteLevels: 메뉴·홈 검색의 테마·등급</li>
 *   <li>requestPath: 상단 메뉴의 현재 탭 표시</li>
 *   <li>showHistoryPopup: 고객 로그인 직후 처음 그리는 화면에서만 true (UC2 (4))</li>
 * </ul>
 * 화면을 그릴 때만 넣는다. 다른 화면으로 이동(redirect)하는 응답에서는 팝업 표시를 꺼내지 않는다.
 */
@Component
public class LayoutModelInterceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView mv) {
        String view = mv == null ? null : mv.getViewName();
        if (mv == null || mv.getView() instanceof RedirectView || (view != null && view.startsWith("redirect:"))
                || (view != null && view.contains("/_"))) { // 이동 응답이나 화면 조각(_로 시작)에는 레이아웃 값이 필요 없다
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
