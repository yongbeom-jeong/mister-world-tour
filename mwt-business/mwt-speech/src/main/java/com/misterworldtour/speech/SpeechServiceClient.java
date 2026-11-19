package com.misterworldtour.speech;

/**
 * 외부 음성인식 서비스 클라이언트 (설계 8.1 SpeechServiceClient). 업체 미정.
 * 기본 {@link BrowserTranscriptClient} 는 브라우저가 이미 인식한 문장을 받는다.
 */
public interface SpeechServiceClient {

    /** audio 는 서버 측 인식 업체용 (지금은 쓰지 않음). */
    record VoiceInput(String transcript, byte[] audio, String audioFormat) {
        public static VoiceInput ofTranscript(String transcript) {
            return new VoiceInput(transcript, new byte[0], "");
        }
    }

    record Recognized(boolean ok, String text, String error) {
        static Recognized ok(String text) {
            return new Recognized(true, text, "");
        }

        static Recognized fail(String error) {
            return new Recognized(false, "", error);
        }
    }

    Recognized recognize(VoiceInput input);
}
