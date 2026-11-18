package com.misterworldtour.speech;

import com.misterworldtour.speech.SpeechRecognitionAPI.Status;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionContext;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionResult;
import com.misterworldtour.speech.SpeechServiceClient.Recognized;
import com.misterworldtour.speech.SpeechServiceClient.VoiceInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 음성 선택 처리 (설계 8.1 SpeechRecognitionManager). SpeechServiceClient 로 문장을 인식하고
 * VoiceSelectionInterpreter 로 선택 정보를 해석해 화면에 돌려준다. 인식·연동 실패, 모호한 명령, 지원하지 않는 명령을 구분한다.
 */
@Service
public class SpeechRecognitionManager implements SpeechRecognitionAPI {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecognitionManager.class);

    /** Web Speech API 오류 코드 → 기록용 설명. 목록 밖 코드는 'other' 로 묶는다 (로그 오염 방지). */
    static final Map<String, String> BROWSER_ERRORS = Map.of(
            "no-speech", "음성 없음", "not-allowed", "마이크 권한 거부", "audio-capture", "마이크 없음",
            "network", "인식 서비스 연결 실패", "aborted", "인식 중단", "service-not-allowed", "인식 서비스 사용 불가",
            "language-not-supported", "언어 미지원", "other", "기타");

    private final SpeechServiceClient client;
    private final VoiceSelectionInterpreter interpreter;

    SpeechRecognitionManager(SpeechServiceClient client, VoiceSelectionInterpreter interpreter) {
        this.client = client;
        this.interpreter = interpreter;
    }

    /** 인식(SpeechServiceClient.recognize) → 해석(VoiceSelectionInterpreter.interpretSelection). 인식 실패·연동 오류는 예외 대신 결과로 알린다. */
    @Override
    public VoiceSelectionResult recognizeSelection(VoiceInput voiceInput, VoiceSelectionContext context) {
        Recognized recognized;
        try {
            recognized = client.recognize(voiceInput);
        } catch (RuntimeException e) {
            log.error("음성인식 연동 실패", e);
            recognized = Recognized.fail("음성인식 서비스에 연결하지 못했습니다.");
        }
        if (!recognized.ok()) {
            log.warn("음성 선택 실패 단계=인식 오류={}", recognized.error());
            return VoiceSelectionResult.of(Status.RECOGNITION_FAILED, recognized.error() + " 다시 말하거나 화면에서 직접 선택해 주세요.");
        }
        VoiceSelectionResult result = interpreter.interpretSelection(recognized.text(), context).withText(recognized.text());
        if (result.status() != Status.SUCCESS) {
            log.info("음성 선택 {} text={}", result.status(), recognized.text());
        }
        return result;
    }

    /** 브라우저에서 끝난 음성 인식 실패를 기록한다 (UC4 4a·7a). 서버까지 문장이 오지 않은 실패만 해당. */
    public String recordBrowserFailure(String errorCode, Long memberId) {
        String code = BROWSER_ERRORS.containsKey(errorCode) ? errorCode : "other";
        log.warn("음성 선택 실패 단계=브라우저 인식 오류={}({}) member={}", code, BROWSER_ERRORS.get(code), memberId);
        return code;
    }
}
