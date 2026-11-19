package com.misterworldtour.ui;

import com.misterworldtour.members.StaffRoleRequired;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptException;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptPreparationResult;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptResponse;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptStatus;
import com.misterworldtour.souvenir.SouvenirManagementAPI;
import com.misterworldtour.travel.Theme;

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
 * 직원 기념품 입고 화면 (설계 3.1 SouvenirStockView, UC8, 상태도 11.3).
 * 준비 → 등록을 이어 호출하고, 응답 불명이면 같은 요청 키로만 다시 확인한다.
 */
@Controller
@RequestMapping("/souvenir/staff")
@StaffRoleRequired
public class SouvenirStockView {

    private static final Logger log = LoggerFactory.getLogger(SouvenirStockView.class);

    private final SouvenirManagementAPI souvenirManager;

    SouvenirStockView(SouvenirManagementAPI souvenirManager) {
        this.souvenirManager = souvenirManager;
    }

    static Map<String, Object> souvenirJson(Souvenir s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getSouvenirId());
        m.put("name", s.getSouvenirName());
        m.put("theme", s.getTheme().getLabel());
        m.put("stock_quantity", s.getStockQuantity());
        return m;
    }

    static List<SouvenirAdminView.ThemeGroup> byTheme(List<Souvenir> list) {
        Map<Theme, List<Souvenir>> map = new LinkedHashMap<>();
        for (Theme t : Theme.values()) {
            map.put(t, new ArrayList<>());
        }
        list.forEach(s -> map.get(s.getTheme()).add(s));
        return map.entrySet().stream().map(e -> new SouvenirAdminView.ThemeGroup(e.getKey(), e.getValue())).toList();
    }

    /** 입고 화면. 목록은 열린 뒤 LoadSouvenirs 로 불러온다. */
    @GetMapping("/stock/")
    String showStockReceipt() {
        return "souvenir/stock";
    }

    /** LoadSouvenirs: 조회 실패는 빈 목록과 구분해 오류로 응답. */
    @GetMapping("/stock/list/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> loadSouvenirs() {
        try {
            return ResponseEntity.ok(Map.of("ok", true,
                    "souvenirs", souvenirManager.loadSouvenirs().stream().map(SouvenirStockView::souvenirJson).toList()));
        } catch (RuntimeException e) {
            log.error("기념품 목록 조회 실패", e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "기념품 목록을 불러오지 못했습니다."));
        }
    }

    /** PrepareStockReceipt: 새 입고 요청 키. */
    @PostMapping("/stock/prepare/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> prepareStockReceipt(
            @RequestParam(name = "souvenir_id", required = false) String souvenirId,
            @RequestParam(required = false) String quantity) {
        try {
            StockReceiptPreparationResult p = souvenirManager.prepareStockReceipt(souvenirId, quantity);
            return ResponseEntity.ok(Map.of("ok", true, "request_key", p.requestKey(), "souvenir_id", p.souvenirId(),
                    "souvenir_name", p.souvenirName(), "quantity", p.quantity()));
        } catch (StockReceiptException e) {
            return ResponseEntity.status(e.getStatus() == StockReceiptStatus.PERMISSION_DENIED ? 403 : 400)
                    .body(Map.of("ok", false, "status", e.getStatus().name(), "message", e.getMessage()));
        }
    }

    /** RecordSouvenirStockReceipt: 같은 키로 재시도해도 재고는 한 번만 는다. */
    @PostMapping("/stock/record/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> recordSouvenirStockReceipt(
            @RequestParam(name = "souvenir_id", required = false) String souvenirId,
            @RequestParam(required = false) String quantity,
            @RequestParam(name = "request_key", required = false) String requestKey) {
        StockReceiptResponse res = souvenirManager.recordSouvenirStockReceipt(souvenirId, quantity, requestKey);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", res.status() == StockReceiptStatus.SUCCESS);
        body.put("status", res.status().name());
        body.put("message", res.message());
        body.put("already_recorded", res.alreadyRecorded());
        body.put("stock_refresh_succeeded", res.stockRefreshSucceeded());
        body.put("current_souvenir", res.currentSouvenir() == null ? null : souvenirJson(res.currentSouvenir()));
        if (res.receipt() != null) {
            body.put("receipt", Map.of("id", res.receipt().getReceiptId(), "quantity", res.receipt().getQuantity(),
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
}
