package com.misterworldtour.ui;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.travel.TravelManagementAPI;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 이전 여행 이력 팝업 (설계 3.1 TravelHistoryView, UC3). */
@Controller
@CustomerRequired
public class TravelHistoryView {

    private static final Logger log = LoggerFactory.getLogger(TravelHistoryView.class);

    private final TravelManagementAPI travelManager;

    TravelHistoryView(TravelManagementAPI travelManager) {
        this.travelManager = travelManager;
    }

    /** 팝업이 불러가는 HTML 조각. 조회 실패는 '이력 없음'과 구분. */
    @GetMapping("/travel/history/")
    String viewPreviousTravelHistory(@AuthenticationPrincipal LoginSession me, Model model, HttpServletResponse response) {
        try {
            model.addAttribute("histories", travelManager.viewPreviousTravelHistory());
            return "travel/_history_list";
        } catch (RuntimeException e) {
            log.error("여행 이력 조회 실패 member={}", me.getMemberId(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return "travel/_history_error";
        }
    }
}
