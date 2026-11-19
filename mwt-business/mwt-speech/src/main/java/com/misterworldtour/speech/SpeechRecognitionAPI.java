package com.misterworldtour.speech;

import com.misterworldtour.speech.SpeechServiceClient.VoiceInput;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 선택 (설계 8.1 SpeechRecognitionAPI).
 * 테마·투어 등급 조회 조건 지정과 현재 화면 목록에서 상품 선택만 지원한다.
 */
public interface SpeechRecognitionAPI {


    enum Status { SUCCESS, RECOGNITION_FAILED, AMBIGUOUS, UNSUPPORTED }

    enum Kind {
        FILTER,
        PRODUCT
    }

    record ProductCandidate(long productId, String name, String destination) {
    }

    /** 현재 화면의 테마·등급과 보이는 순서대로의 상품 목록. */
    record VoiceSelectionContext(Theme theme, TourLevel level, List<ProductCandidate> products) {
    }

    record VoiceSelectionResult(Status status, String message, Kind kind, Theme theme, TourLevel level, Long productId,
                                String recognizedText) {

        public static VoiceSelectionResult of(Status status, String message) {
            return new VoiceSelectionResult(status, message, null, null, null, null, "");
        }

        public VoiceSelectionResult withText(String text) {
            return new VoiceSelectionResult(status, message, kind, theme, level, productId, text);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status.name());
            m.put("message", message);
            m.put("kind", kind == null ? null : kind.name());
            m.put("theme", theme == null ? null : theme.name());
            m.put("tour_level", level == null ? null : level.name());
            m.put("product_id", productId);
            m.put("recognized_text", recognizedText);
            return m;
        }
    }

    /** 설계 RecognizeSelection. 실패·모호·미지원은 예외 대신 결과로 알린다. */
    VoiceSelectionResult recognizeSelection(VoiceInput voiceInput, VoiceSelectionContext context);

    /** 브라우저에서 끝난 인식 실패를 기록하고 안내 문구를 돌려준다. */
    String recordBrowserFailure(String errorCode, Long memberId);
}
