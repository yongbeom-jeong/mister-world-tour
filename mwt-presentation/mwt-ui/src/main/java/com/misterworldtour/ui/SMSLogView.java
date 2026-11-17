package com.misterworldtour.ui;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.members.StaffRoleRequired;
import com.misterworldtour.sms.SMSMessage;
import com.misterworldtour.sms.SMSNotificationAPI;
import com.misterworldtour.ui.Messages.Level;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.Objects;

/** 직원의 문자 발송 기록 조회와 재발송 화면 (설계 이후 추가). 발송 기록은 SMSMessage 로 남는다. */
@Controller
@RequestMapping("/sms/staff/logs")
@StaffRoleRequired
public class SMSLogView {

    private final SMSNotificationAPI sms;
    private final MemberManagementAPI members;

    SMSLogView(SMSNotificationAPI sms, MemberManagementAPI members) {
        this.sms = sms;
        this.members = members;
    }

    @GetMapping("/")
    String logList(@RequestParam(required = false) String filter, @RequestParam(required = false) String page,
                   HttpServletRequest request, Model model) {
        boolean problemsOnly = "problems".equals(filter);
        int pageNumber = page != null && page.matches("\\d{1,9}") ? Math.max(Integer.parseInt(page), 1) : 1;
        Page<SMSMessage> result = sms.loadMessages(problemsOnly, pageNumber - 1);
        if (pageNumber > Math.max(result.getTotalPages(), 1)) { // 없는 페이지는 마지막 페이지
            pageNumber = Math.max(result.getTotalPages(), 1);
            result = sms.loadMessages(problemsOnly, pageNumber - 1);
        }
        Map<Long, String> resentBy = members.namesByIds(result.getContent().stream().map(SMSMessage::getResentById)
                .filter(Objects::nonNull).toList());
        String query = request.getQueryString();
        model.addAttribute("page", result);
        model.addAttribute("pageNumber", pageNumber);
        model.addAttribute("problemsOnly", problemsOnly);
        model.addAttribute("problemCount", sms.problemCount());
        model.addAttribute("resentBy", resentBy);
        model.addAttribute("currentPath", request.getRequestURI() + (query == null ? "" : "?" + query));
        return "sms/log_list";
    }

    @PostMapping("/{logId}/resend/")
    String resend(@AuthenticationPrincipal LoginSession me, @PathVariable long logId,
                  @RequestParam(name = "confirm_duplicate", required = false) String confirmDuplicate,
                  @RequestParam(required = false) String next, RedirectAttributes redirect) {
        try {
            SMSMessage log = sms.resendMessage(logId, me.getMemberId(), "1".equals(confirmDuplicate));
            String text = log.getRecipientName() + "님에게 다시 보냈습니다: " + log.getStatus().getLabel();
            if (!log.getLastError().isEmpty() && log.getStatus() != SMSMessage.Status.SUCCESS) {
                text += " (" + log.getLastError() + ")";
            }
            Messages.flash(redirect, log.getStatus() == SMSMessage.Status.SUCCESS ? Level.SUCCESS : Level.WARNING, text);
        } catch (SMSNotificationAPI.ResendException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
        }
        boolean safe = next != null && next.startsWith("/sms/") && !next.contains("//") && !next.contains("\\");
        return "redirect:" + (safe ? next : "/sms/staff/logs/");
    }
}
