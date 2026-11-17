package com.misterworldtour.speech;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.speech.SpeechService.Context;
import com.misterworldtour.speech.SpeechService.ProductCandidate;
import com.misterworldtour.travel.BookingService;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** 음성·문장으로 상품 찾기 (SelectByVoice, Django 판 speech/views.py). 화면 상태는 브라우저가 바꾼다. */
@RestController
@RequestMapping("/speech")
@CustomerRequired
class SpeechController {

    private final SpeechService speech;
    private final BookingService booking;
    private final JsonMapper json;

    SpeechController(SpeechService speech, BookingService booking, JsonMapper json) {
        this.speech = speech;
        this.booking = booking;
        this.json = json;
    }

    private Map<String, Object> parse(String body) {
        return json.readValue(body == null || body.isBlank() ? "{}" : body, new TypeReference<Map<String, Object>>() { });
    }

    /** 인식 문장 + 현재 화면 조건 → 선택 값 (JSON). */
    @PostMapping("/recognize/")
    ResponseEntity<Map<String, Object>> recognize(@RequestBody(required = false) String body) {
        Map<String, Object> data;
        try {
            data = parse(body);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "RECOGNITION_FAILED", "message", "잘못된 요청입니다."));
        }
        Theme theme = Theme.parse(String.valueOf(data.get("theme")));
        TourLevel level = TourLevel.parse(String.valueOf(data.get("tour_level")));
        // 문맥의 상품 목록은 서버가 같은 조건으로 다시 조회한다 (화면과 같은 순서)
        List<ProductCandidate> products = booking.bookableProducts(theme, level).stream()
                .map(p -> new ProductCandidate(p.getId(), p.getName(), p.getDestination())).toList();
        Object transcript = data.get("transcript");
        SpeechService.Result result = speech.recognizeSelection(
                SpeechClient.VoiceInput.ofTranscript(transcript == null ? "" : transcript.toString()),
                new Context(theme, level, products));
        return ResponseEntity.ok(result.toMap());
    }

    /** 브라우저 음성 인식 실패 기록 (UC4 4a·7a). 화면 안내는 브라우저가 이미 했으므로 본문 없이 응답. */
    @PostMapping("/failure/")
    ResponseEntity<Void> browserFailure(@AuthenticationPrincipal MemberPrincipal me,
                                        @RequestBody(required = false) String body) {
        String error = "";
        try {
            Object e = parse(body).get("error");
            error = e == null ? "" : e.toString();
        } catch (JacksonException ignored) {
            // 잘못된 본문도 '기타'로 기록
        }
        speech.recordBrowserFailure(error.length() > 40 ? error.substring(0, 40) : error, me.getId());
        return ResponseEntity.noContent().build();
    }
}
