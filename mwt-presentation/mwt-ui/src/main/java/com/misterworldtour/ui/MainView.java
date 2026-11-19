package com.misterworldtour.ui;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.SouvenirManagementAPI;
import com.misterworldtour.travel.TravelManagementAPI;

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

/** 역할별 홈 화면과 사진 출처 (설계 3.1 MainView). OpenView 는 메뉴·홈 링크로 한다. */
@Controller
public class MainView {

    private static final int FEATURED = 8;
    private static final int RECENT = 5;
    /** 대시보드 '재고 부족' 기준 (임시). */
    static final int LOW_STOCK = 10;

    private final JsonMapper jsonMapper;
    private final TravelManagementAPI travel;
    private final SouvenirManagementAPI souvenirs;
    private volatile List<Map<String, String>> credits;

    MainView(JsonMapper jsonMapper, TravelManagementAPI travel, SouvenirManagementAPI souvenirs) {
        this.jsonMapper = jsonMapper;
        this.travel = travel;
        this.souvenirs = souvenirs;
    }

    /** ShowHome */
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
