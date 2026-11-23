package com.misterworldtour.ui;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.speech.VoiceBookingAPI;
import com.misterworldtour.speech.VoiceBookingAPI.VoiceDialogState;
import com.misterworldtour.speech.VoiceBookingAPI.VoiceDialogTurn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 음성 예약 화면 (설계 이후 추가, 최종발표 시나리오 2.1). 대화 상태는 세션에 둔다. */
@Controller
@CustomerRequired
public class VoiceBookingView {

    static final String STATE_KEY = "voice_booking";

    private final VoiceBookingAPI voiceBooking;
    private final JsonMapper json;

    VoiceBookingView(VoiceBookingAPI voiceBooking, JsonMapper json) {
        this.voiceBooking = voiceBooking;
        this.json = json;
    }

    /** 들어올 때마다 새 대화 (인사부터). */
    @GetMapping("/speech/booking/")
    String showVoiceBooking(HttpSession session, Model model) {
        VoiceDialogTurn turn = voiceBooking.startVoiceBooking();
        session.setAttribute(STATE_KEY, turn.state());
        model.addAttribute("greeting", turn.reply());
        return "speech/voice_booking";
    }

    @PostMapping("/speech/booking/talk/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> talk(@RequestBody(required = false) String body, HttpSession session,
                                             HttpServletRequest request) {
        String transcript;
        try {
            Object t = json.readValue(body == null || body.isBlank() ? "{}" : body,
                    new TypeReference<Map<String, Object>>() { }).get("transcript");
            transcript = t == null ? "" : t.toString();
        } catch (JacksonException | ClassCastException e) {
            return ResponseEntity.badRequest().body(Map.of("reply", "잘못된 요청입니다."));
        }
        VoiceDialogState state = session.getAttribute(STATE_KEY) instanceof VoiceDialogState s ? s : null;
        VoiceDialogTurn turn = voiceBooking.talk(state, transcript);
        session.setAttribute(STATE_KEY, turn.state());
        return ResponseEntity.ok(toJson(turn, request));
    }

    @PostMapping("/speech/booking/reset/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> reset(HttpSession session, HttpServletRequest request) {
        VoiceDialogTurn turn = voiceBooking.startVoiceBooking();
        session.setAttribute(STATE_KEY, turn.state());
        return ResponseEntity.ok(toJson(turn, request));
    }

    private static Map<String, Object> toJson(VoiceDialogTurn turn, HttpServletRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reply", turn.reply());
        m.put("done", turn.done());
        m.put("application_id", turn.applicationId());
        m.put("application_url", turn.applicationId() == null ? null
                : request.getContextPath() + "/travel/applications/" + turn.applicationId() + "/");
        m.put("selection", turn.selection());
        m.put("fallback", turn.fallback());
        return m;
    }
}
