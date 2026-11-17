package com.misterworldtour.common;

import com.misterworldtour.members.LoginHandlers;
import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.travel.Display;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * 모든 화면(레이아웃)에서 쓰는 값을 넣는다. Django 판의 context processor 들과 같은 역할.
 * <ul>
 *   <li>currentMember: 로그인한 회원 (없으면 null)</li>
 *   <li>siteThemes·siteLevels: 메뉴·홈 검색의 테마·등급</li>
 *   <li>requestPath: 상단 메뉴의 현재 탭 표시</li>
 *   <li>showHistoryPopup: 고객 로그인 직후 처음 그리는 화면에서만 true (UC2 (4))</li>
 * </ul>
 * 화면을 그릴 때만 넣는다. 다른 화면으로 이동(redirect)하는 응답에서는 팝업 표시를 꺼내지 않는다.
 */
class LayoutModelInterceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView mv) {
        if (mv == null || mv.getView() instanceof RedirectView
                || (mv.getViewName() != null && mv.getViewName().startsWith("redirect:"))) {
            return;
        }
        MemberPrincipal member = currentMember();
        mv.addObject("currentMember", member);
        mv.addObject("siteThemes", Display.themes());
        mv.addObject("siteLevels", Display.levels());
        mv.addObject("requestPath", request.getRequestURI().substring(request.getContextPath().length()));
        mv.addObject("showHistoryPopup", popHistoryPopup(request, member));
    }

    static MemberPrincipal currentMember() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof MemberPrincipal p ? p : null;
    }

    private static boolean popHistoryPopup(HttpServletRequest request, MemberPrincipal member) {
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
