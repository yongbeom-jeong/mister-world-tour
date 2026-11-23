package com.misterworldtour.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/** VOICE_DIALOG_CLIENT 설정으로 음성 예약 대화 상대를 고른다. */
@Configuration
class VoiceDialogConfig {

    private static final Logger log = LoggerFactory.getLogger(VoiceDialogConfig.class);

    @Bean
    VoiceDialogClient voiceDialogClient(VoiceDialogProperties p) {
        String name = p.client() == null ? "" : p.client().toLowerCase(Locale.ROOT);
        if (name.contains("openai")) {
            if (p.openaiApiKey() == null || p.openaiApiKey().isBlank()) {
                // 발표 중 서버가 안 뜨는 것보다 규칙 대화로라도 시연되는 편이 낫다
                log.error("VOICE_DIALOG_CLIENT=OpenAI 인데 OPENAI_API_KEY 가 비어 있어 규칙 대화(Scripted)로 시작합니다 (.env 확인)");
                return new ScriptedDialogClient();
            }
            log.info("음성 예약 대화: OpenAI 모델 {}", p.openaiModel());
            return new OpenAIDialogClient(p.openaiApiKey(), p.openaiModel(), p.openaiBaseUrl(), p.timeout());
        }
        return new ScriptedDialogClient();
    }
}
