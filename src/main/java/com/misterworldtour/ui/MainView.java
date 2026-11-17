package com.misterworldtour.ui;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.SouvenirManager;
import com.misterworldtour.travel.TravelManager;

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

/**
 * 역할에 맞는 홈 화면과 메뉴 (설계 3.1 MainView), 사진 출처.
 * 각 기능 화면으로의 이동(OpenView)은 상단 메뉴·홈의 링크로 한다.
 */
@Controller
public class MainView {

    private static final int FEATURED = 8;
    private static final int RECENT = 5;
    /** 직원 대시보드에서 '재고 부족'으로 강조할 기준 (임시). */
    static final int LOW_STOCK = 10;

    private final JsonMapper jsonMapper;
    private final TravelManager travel;
    private final SouvenirManager souvenirs;
    private volatile List<Map<String, String>> credits;

    MainView(JsonMapper jsonMapper, TravelManager travel, SouvenirManager souvenirs) {
        this.jsonMapper = jsonMapper;
        this.travel = travel;
        this.souvenirs = souvenirs;
    }

    /** ShowHome: 역할별 홈. 고객·비로그인은 테마 검색과 곧 출발하는 여행, 직원은 직원 메뉴(통계·최근 상품·재고). */
    @GetMapping("/")
    String showHome(@AuthenticationPrincipal LoginSession member, Model model) {
        if (member == null || member.isCustomer()) {
            model.addAttribute("featured", travel.loadProducts(null, null).stream().limit(FEATURED).toList());
        }
        if (member != null && member.isCustomer()) {
            model.addAttribute("myCount", travel.activeApplicationCount(member.getMemberId()));
        }
        if (member != null && member.isStaffMember()) {
            List<Souvenir> all = souvenirs.loadSouvenirs();
            model.addAttribute("stats", travel.staffDashboardCounts());
            model.addAttribute("recentProducts", travel.recentProducts(RECENT));
            model.addAttribute("souvenirs", all);
            model.addAttribute("lowStock", all.stream().filter(s -> s.getStockQuantity() < LOW_STOCK).toList());
            model.addAttribute("lowStockLimit", LOW_STOCK);
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
