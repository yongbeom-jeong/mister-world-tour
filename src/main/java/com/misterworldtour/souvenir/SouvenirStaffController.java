package com.misterworldtour.souvenir;

import com.misterworldtour.common.Messages;
import com.misterworldtour.common.Messages.Level;
import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.members.MemberService;
import com.misterworldtour.members.StaffRoleRequired;
import com.misterworldtour.souvenir.SouvenirService.SouvenirNotFound;
import com.misterworldtour.souvenir.SouvenirService.SouvenirRegistrationException;
import com.misterworldtour.souvenir.StockReceiptService.Pending;
import com.misterworldtour.souvenir.StockReceiptService.ReceiptException;
import com.misterworldtour.souvenir.StockReceiptService.Status;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelService;
import com.misterworldtour.travel.TravelService.ApplicationSummary;
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
 * 직원 화면: 기념품 입고(UC8)·품목 등록·수정·삭제·차감 내역 (Django 판 souvenir/views.py).
 * 입고 JSON 요청(list·prepare·record)의 로그인 만료·권한 없음은 {@code SecurityConfig} 가 JSON 403 으로 응답한다.
 */
@Controller
@RequestMapping("/souvenir/staff")
@StaffRoleRequired
class SouvenirStaffController {

    /** 요청 키별 입고 준비 문맥. */
    static final String PENDING_SESSION_KEY = "stock_receipt_pending";
    static final int ISSUES_PER_PAGE = 30;
    private static final Logger log = LoggerFactory.getLogger(SouvenirStaffController.class);

    /** 테마 한 줄: 테마와 그 테마의 품목들. */
    record ThemeGroup(Theme theme, List<Souvenir> items) {
    }

    /** 차감 내역 한 줄 (신청·상품·고객은 다른 패키지 서비스에서 받아 붙인다). */
    record IssueRow(StockIssue issue, ApplicationSummary application, String memberName) {
    }

    private final SouvenirService souvenirs;
    private final StockReceiptService receipts;
    private final TravelService travel;
    private final MemberService members;

    SouvenirStaffController(SouvenirService souvenirs, StockReceiptService receipts, TravelService travel,
                            MemberService members) {
        this.souvenirs = souvenirs;
        this.receipts = receipts;
        this.travel = travel;
        this.members = members;
    }

    static Map<String, Object> souvenirJson(Souvenir s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("theme", s.getTheme().getLabel());
        m.put("stock_quantity", s.getStockQuantity());
        return m;
    }

    private static List<ThemeGroup> byTheme(List<Souvenir> list) {
        Map<Theme, List<Souvenir>> map = new LinkedHashMap<>();
        for (Theme t : Theme.values()) {
            map.put(t, new ArrayList<>());
        }
        list.forEach(s -> map.get(s.getTheme()).add(s));
        return map.entrySet().stream().map(e -> new ThemeGroup(e.getKey(), e.getValue())).toList();
    }

    // ---------------------------------------------------------------- 입고 (UC8)

    /** 입고 화면. 목록은 페이지가 열린 뒤 JSON 으로 불러온다. */
    @GetMapping("/stock/")
    String stockPage() {
        return "souvenir/stock";
    }

    /** LoadSouvenirs: 조회 실패는 빈 목록·재고 0과 구분해 오류로 응답. */
    @GetMapping("/stock/list/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> souvenirList() {
        try {
            return ResponseEntity.ok(Map.of("ok", true,
                    "souvenirs", souvenirs.loadSouvenirs().stream().map(SouvenirStaffController::souvenirJson).toList()));
        } catch (RuntimeException e) {
            log.error("기념품 목록 조회 실패", e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "기념품 목록을 불러오지 못했습니다."));
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Pending> pending(HttpSession session) {
        Object o = session.getAttribute(PENDING_SESSION_KEY);
        return o instanceof LinkedHashMap<?, ?> m ? (LinkedHashMap<String, Pending>) m : new LinkedHashMap<>();
    }

    @PostMapping("/stock/prepare/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> prepareReceipt(@AuthenticationPrincipal MemberPrincipal me,
                                                       @RequestParam(name = "souvenir_id", required = false) String souvenirId,
                                                       @RequestParam(required = false) String quantity,
                                                       HttpSession session) {
        LinkedHashMap<String, Pending> pending = pending(session);
        try {
            StockReceiptService.Prepared p = receipts.prepare(pending, me.getId(), souvenirId, quantity);
            session.setAttribute(PENDING_SESSION_KEY, pending);
            return ResponseEntity.ok(Map.of("ok", true, "request_key", p.requestKey(), "souvenir_id", p.souvenirId(),
                    "souvenir_name", p.souvenirName(), "quantity", p.quantity()));
        } catch (ReceiptException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "status", e.getStatus().name(),
                    "message", e.getMessage()));
        }
    }

    @PostMapping("/stock/record/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> recordReceipt(@AuthenticationPrincipal MemberPrincipal me,
                                                      @RequestParam(name = "souvenir_id", required = false) String souvenirId,
                                                      @RequestParam(required = false) String quantity,
                                                      @RequestParam(name = "request_key", required = false) String requestKey,
                                                      HttpSession session) {
        LinkedHashMap<String, Pending> pending = pending(session);
        StockReceiptService.Response res = receipts.record(pending, me.getId(), souvenirId, quantity, requestKey);
        session.setAttribute(PENDING_SESSION_KEY, pending);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", res.status() == Status.SUCCESS);
        body.put("status", res.status().name());
        body.put("message", res.message());
        body.put("already_recorded", res.alreadyRecorded());
        body.put("stock_refresh_succeeded", res.stockRefreshSucceeded());
        body.put("current_souvenir", res.currentSouvenir() == null ? null : souvenirJson(res.currentSouvenir()));
        if (res.receipt() != null) {
            body.put("receipt", Map.of("id", res.receipt().getId(), "quantity", res.receipt().getQuantity(),
                    "stock_after", res.receipt().getStockAfter()));
        }
        int status = switch (res.status()) {
            case SUCCESS -> 200;
            case INVALID_INPUT -> 400;
            case PERMISSION_DENIED -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).body(body);
    }

    // ---------------------------------------------------------------- 품목 등록

    /** 이름과 연결할 테마. 등록 후 입고 화면에서 그 품목을 선택한 상태로 연다. */
    @GetMapping("/souvenirs/new/")
    String registerPage(Model model) {
        model.addAttribute("values", Map.of("name", "", "theme", ""));
        model.addAttribute("errors", Map.of());
        model.addAttribute("groups", byTheme(souvenirs.loadSouvenirs()));
        return "souvenir/register";
    }

    @PostMapping("/souvenirs/new/")
    String register(@RequestParam(required = false) String name, @RequestParam(required = false) String theme,
                    Model model, RedirectAttributes redirect) {
        String n = name == null ? "" : name.strip();
        try {
            Souvenir s = souvenirs.registerSouvenir(n, Theme.parse(theme));
            Messages.flash(redirect, Level.SUCCESS, "'%s'을(를) %s에 등록했습니다. 입고 수량을 등록해 주세요."
                    .formatted(s.getName(), s.getTheme().getLabel()));
            return "redirect:/souvenir/staff/stock/?selected=" + s.getId();
        } catch (SouvenirRegistrationException e) {
            model.addAttribute("values", Map.of("name", n, "theme", theme == null ? "" : theme));
            model.addAttribute("errors", e.getErrors());
            model.addAttribute("groups", byTheme(souvenirs.loadSouvenirs()));
            return "souvenir/register";
        }
    }

    // ---------------------------------------------------------------- 품목 관리 (수정·삭제)·차감 내역

    private void warnIfThemeEmpty(RedirectAttributes redirect, Theme theme) {
        if (!souvenirs.themeHasSouvenir(theme)) {
            Messages.flash(redirect, Level.WARNING, theme.getLabel()
                    + "에 연결된 기념품이 없어 이 테마 상품은 신청할 수 없습니다. 기념품을 등록하고 입고해 주세요.");
        }
    }

    @GetMapping("/souvenirs/")
    String managePage(Model model) {
        model.addAttribute("groups", byTheme(souvenirs.loadSouvenirs()));
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
        return editView(s, Map.of("name", s.getName(), "theme", s.getTheme().name()), Map.of(), model);
    }

    private String editView(Souvenir s, Map<String, String> values, Map<String, String> errors, Model model) {
        model.addAttribute("souvenir", s);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("themes", Theme.values());
        model.addAttribute("issueCount", souvenirs.issueCount(s.getId()));
        model.addAttribute("receiptCount", souvenirs.receiptCount(s.getId()));
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
            Messages.flash(redirect, Level.SUCCESS, "'" + updated.getName() + "' 정보를 수정했습니다.");
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
        Messages.flash(redirect, Level.SUCCESS, removed ? "'" + s.getName() + "'을(를) 삭제했습니다."
                : "'" + s.getName() + "'을(를) 삭제했습니다. 입고·차감 내역은 기록으로 남겨 둡니다.");
        warnIfThemeEmpty(redirect, s.getTheme());
        return "redirect:/souvenir/staff/souvenirs/";
    }

    @GetMapping("/souvenirs/issues/")
    String issueList(@RequestParam(required = false) String souvenir, @RequestParam(required = false) String page,
                     Model model) {
        Long selected = souvenir != null && souvenir.matches("\\d{1,18}") ? Long.valueOf(souvenir) : null;
        int pageNumber = page != null && page.matches("\\d{1,9}") ? Math.max(Integer.parseInt(page), 1) : 1;
        Page<StockIssue> result = souvenirs.loadIssues(selected, pageNumber - 1, ISSUES_PER_PAGE);
        if (pageNumber > Math.max(result.getTotalPages(), 1)) { // 없는 페이지는 마지막 페이지 (Django get_page 와 같음)
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
