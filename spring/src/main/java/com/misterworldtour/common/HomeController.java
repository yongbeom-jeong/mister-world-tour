package com.misterworldtour.common;

import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.travel.BookingService;
import com.misterworldtour.travel.TravelService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** 홈(설계 UI MainView)과 사진 출처. */
@Controller
class HomeController {

    private static final int FEATURED = 8;
    private static final int RECENT = 5;

    private final JsonMapper jsonMapper;
    private final BookingService booking;
    private final TravelService travel;
    private volatile List<Map<String, String>> credits;

    HomeController(JsonMapper jsonMapper, BookingService booking, TravelService travel) {
        this.jsonMapper = jsonMapper;
        this.booking = booking;
        this.travel = travel;
    }

    /** 역할별 홈. 고객·비로그인은 테마 검색과 곧 출발하는 여행, 직원은 직원 메뉴(통계·최근 상품). */
    @GetMapping("/")
    String home(@AuthenticationPrincipal MemberPrincipal member, Model model) {
        if (member == null || member.isCustomer()) {
            model.addAttribute("featured", booking.bookableProducts(null, null).stream().limit(FEATURED).toList());
        }
        if (member != null && member.isCustomer()) {
            model.addAttribute("myCount", booking.activeApplicationCount(member.getId()));
        }
        if (member != null && member.isStaffMember()) {
            model.addAttribute("stats", travel.staffDashboardCounts());
            model.addAttribute("recentProducts", travel.recentProducts(RECENT));
        }
        return "home";
    }

    /** 사진 출처 (CC BY·BY-SA 라이선스의 저작자 표시 의무). */
    @GetMapping("/credits/")
    String credits(Model model) {
        model.addAttribute("credits", loadCredits());
        return "credits";
    }

    private List<Map<String, String>> loadCredits() {
        List<Map<String, String>> c = credits;
        if (c == null) {
            try (InputStream in = new ClassPathResource("static/img/photos/credits.json").getInputStream()) {
                c = jsonMapper.readValue(in, new TypeReference<>() { });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            credits = c;
        }
        return c;
    }
}
