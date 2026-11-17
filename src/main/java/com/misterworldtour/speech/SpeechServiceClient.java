package com.misterworldtour.speech;

/**
 * 외부 한국어 음성인식 서비스와 통신하는 클라이언트 (설계 8.1 SpeechServiceClient). 업체 미정이라 인터페이스 뒤에 둔다.
 * 기본 {@link BrowserTranscriptClient} 는 브라우저(Web Speech API, ko-KR)가 이미 인식한 문장을 받는다.
 */
public interface SpeechServiceClient {

    /** 음성 입력. transcript 는 브라우저가 인식한 문장, audio 는 서버 측 인식 업체용 (지금은 쓰지 않음). */
    record VoiceInput(String transcript, byte[] audio, String audioFormat) {
        public static VoiceInput ofTranscript(String transcript) {
            return new VoiceInput(transcript, new byte[0], "");
        }
    }

    /** 인식 결과. */
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
