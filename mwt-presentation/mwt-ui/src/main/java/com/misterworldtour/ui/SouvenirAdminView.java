package com.misterworldtour.ui;

import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.members.StaffRoleRequired;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.SouvenirManagementAPI.SouvenirNotFound;
import com.misterworldtour.souvenir.SouvenirManagementAPI.SouvenirRegistrationException;
import com.misterworldtour.souvenir.SouvenirManagementAPI;
import com.misterworldtour.souvenir.StockIssue;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationSummary;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.ui.Messages.Level;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 직원 기념품 품목 관리 화면 (설계 이후 추가): 등록·수정·삭제, 차감 내역.
 * 재고는 입고와 출발 확정 차감으로만 바뀐다.
 */
@Controller
@RequestMapping("/souvenir/staff")
@StaffRoleRequired
public class SouvenirAdminView {

    static final int ISSUES_PER_PAGE = 30;
    private static final Logger log = LoggerFactory.getLogger(SouvenirAdminView.class);

    public record ThemeGroup(Theme theme, List<Souvenir> items) {
    }

    record IssueRow(StockIssue issue, ApplicationSummary application, String memberName) {
    }

    private final SouvenirManagementAPI souvenirs;
    private final TravelManagementAPI travel;
    private final MemberManagementAPI members;

    SouvenirAdminView(SouvenirManagementAPI souvenirs, TravelManagementAPI travel, MemberManagementAPI members) {
        this.souvenirs = souvenirs;
        this.travel = travel;
        this.members = members;
    }

    @GetMapping("/souvenirs/new/")
    String registerPage(Model model) {
        model.addAttribute("values", Map.of("name", "", "theme", ""));
        model.addAttribute("errors", Map.of());
        model.addAttribute("groups", SouvenirStockView.byTheme(souvenirs.loadSouvenirs()));
        return "souvenir/register";
    }

    @PostMapping("/souvenirs/new/")
    String register(@RequestParam(required = false) String name, @RequestParam(required = false) String theme,
                    Model model, RedirectAttributes redirect) {
        String n = name == null ? "" : name.strip();
        try {
            Souvenir s = souvenirs.registerSouvenir(n, Theme.parse(theme));
            Messages.flash(redirect, Level.SUCCESS, "'%s'을(를) %s에 등록했습니다. 입고 수량을 등록해 주세요."
                    .formatted(s.getSouvenirName(), s.getTheme().getLabel()));
            return "redirect:/souvenir/staff/stock/?selected=" + s.getSouvenirId();
        } catch (SouvenirRegistrationException e) {
            model.addAttribute("values", Map.of("name", n, "theme", theme == null ? "" : theme));
            model.addAttribute("errors", e.getErrors());
            model.addAttribute("groups", SouvenirStockView.byTheme(souvenirs.loadSouvenirs()));
            return "souvenir/register";
        }
    }

    private void warnIfThemeEmpty(RedirectAttributes redirect, Theme theme) {
        if (!souvenirs.themeHasSouvenir(theme)) {
            Messages.flash(redirect, Level.WARNING, theme.getLabel()
                    + "에 연결된 기념품이 없어 이 테마 상품은 신청할 수 없습니다. 기념품을 등록하고 입고해 주세요.");
        }
    }

    @GetMapping("/souvenirs/")
    String managePage(Model model) {
        model.addAttribute("groups", SouvenirStockView.byTheme(souvenirs.loadSouvenirs()));
        return "souvenir/manage";
    }

    @GetMapping("/souvenirs/{souvenirId}/edit/")
    String editPage(@PathVariable long souvenirId, Model model, RedirectAttributes redirect) {
        Souvenir s;
        try {
            s = souvenirs.getSouvenir(souvenirId);
        } catch (SouvenirNotFound e) {
            Messages.flash(redirect, Level.ERROR, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/souvenir/staff/souvenirs/";
        }
        return editView(s, Map.of("name", s.getSouvenirName(), "theme", s.getTheme().name()), Map.of(), model);
    }

    private String editView(Souvenir s, Map<String, String> values, Map<String, String> errors, Model model) {
        model.addAttribute("souvenir", s);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("themes", Theme.values());
        model.addAttribute("issueCount", souvenirs.issueCount(s.getSouvenirId()));
        model.addAttribute("receiptCount", souvenirs.receiptCount(s.getSouvenirId()));
        return "souvenir/edit";
    }

    @PostMapping("/souvenirs/{souvenirId}/edit/")
    String edit(@PathVariable long souvenirId, @RequestParam(required = false) String name,
                @RequestParam(required = false) String theme, Model model, RedirectAttributes redirect) {
        Souvenir before;
        try {
            before = souvenirs.getSouvenir(souvenirId);
        } catch (SouvenirNotFound e) {
            Messages.flash(redirect, Level.ERROR, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/souvenir/staff/souvenirs/";
        }
        String n = name == null ? "" : name.strip();
        Theme oldTheme = before.getTheme();
        try {
            Souvenir updated = souvenirs.updateSouvenir(souvenirId, n, Theme.parse(theme));
            Messages.flash(redirect, Level.SUCCESS, "'" + updated.getSouvenirName() + "' 정보를 수정했습니다.");
            if (oldTheme != updated.getTheme()) {
                warnIfThemeEmpty(redirect, oldTheme);
            }
            return "redirect:/souvenir/staff/souvenirs/";
        } catch (SouvenirRegistrationException e) {
            return editView(before, Map.of("name", n, "theme", theme == null ? "" : theme), e.getErrors(), model);
        } catch (SouvenirNotFound e) {
            Messages.flash(redirect, Level.ERROR, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/souvenir/staff/souvenirs/";
        }
    }

    @PostMapping("/souvenirs/{souvenirId}/delete/")
    String delete(@PathVariable long souvenirId, RedirectAttributes redirect) {
        Souvenir s;
        boolean removed;
        try {
            s = souvenirs.getSouvenir(souvenirId);
            removed = souvenirs.deleteSouvenir(souvenirId);
        } catch (SouvenirNotFound e) {
            Messages.flash(redirect, Level.ERROR, "기념품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/souvenir/staff/souvenirs/";
        }
        Messages.flash(redirect, Level.SUCCESS, removed ? "'" + s.getSouvenirName() + "'을(를) 삭제했습니다."
                : "'" + s.getSouvenirName() + "'을(를) 삭제했습니다. 입고·차감 내역은 기록으로 남겨 둡니다.");
        warnIfThemeEmpty(redirect, s.getTheme());
        return "redirect:/souvenir/staff/souvenirs/";
    }

    @GetMapping("/souvenirs/issues/")
    String issueList(@RequestParam(required = false) String souvenir, @RequestParam(required = false) String page,
                     Model model) {
        Long selected = souvenir != null && souvenir.matches("\\d{1,18}") ? Long.valueOf(souvenir) : null;
        int pageNumber = page != null && page.matches("\\d{1,9}") ? Math.max(Integer.parseInt(page), 1) : 1;
        Page<StockIssue> result = souvenirs.loadIssues(selected, pageNumber - 1, ISSUES_PER_PAGE);
        if (pageNumber > Math.max(result.getTotalPages(), 1)) {
            pageNumber = Math.max(result.getTotalPages(), 1);
            result = souvenirs.loadIssues(selected, pageNumber - 1, ISSUES_PER_PAGE);
        }
        Set<Long> appIds = result.getContent().stream().map(StockIssue::getApplicationId).collect(Collectors.toSet());
        Map<Long, ApplicationSummary> apps = travel.applicationSummaries(appIds);
        Map<Long, String> names = members.namesByIds(apps.values().stream().map(ApplicationSummary::memberId).toList());
        model.addAttribute("rows", result.getContent().stream().map(i -> {
            ApplicationSummary a = apps.get(i.getApplicationId());
            return new IssueRow(i, a, a == null ? "" : names.getOrDefault(a.memberId(), ""));
        }).toList());
        model.addAttribute("page", result);
        model.addAttribute("pageNumber", pageNumber);
        model.addAttribute("selected", selected);
        model.addAttribute("souvenirs", souvenirs.loadAllSouvenirs());
        return "souvenir/issues";
    }
}
