package com.misterworldtour.speech;

import org.springframework.stereotype.Component;

/** 브라우저 Web Speech API 가 이미 인식한 문장을 그대로 사용한다 (Chrome·Edge). */
@Component
class BrowserTranscriptClient implements SpeechClient {

    static final int MAX_LENGTH = 200;

    @Override
    public Recognized recognize(VoiceInput input) {
        String text = input.transcript() == null ? "" : input.transcript().strip();
        if (text.isEmpty()) {
            return Recognized.fail("음성을 인식하지 못했습니다.");
        }
        if (text.length() > MAX_LENGTH) {
            return Recognized.fail("너무 긴 음성입니다. 짧게 다시 말해 주세요.");
        }
        return Recognized.ok(text);
    }
}
