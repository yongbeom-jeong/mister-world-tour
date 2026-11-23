package com.misterworldtour.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 음성 예약 대화 설정 (application.yml 의 mwt.voice-dialog.*, 값은 .env 의 VOICE_DIALOG_CLIENT·OPENAI_*).
 *
 * @param client        VOICE_DIALOG_CLIENT: Scripted(기본, 규칙 대화) / OpenAI(LLM 대화)
 * @param openaiApiKey  OPENAI_API_KEY
 * @param openaiModel   OPENAI_MODEL: 구조화 출력(JSON 스키마)을 지원하는 채팅 모델
 * @param openaiBaseUrl OPENAI_BASE_URL: 보통 바꾸지 않는다
 * @param timeout       VOICE_DIALOG_TIMEOUT: LLM 1회 대기 (초). 넘기면 그 턴은 규칙 대화로 답한다
 */
@ConfigurationProperties(prefix = "mwt.voice-dialog")
public record VoiceDialogProperties(
        @DefaultValue("Scripted") String client,
        @DefaultValue("") String openaiApiKey,
        @DefaultValue("gpt-4o-mini") String openaiModel,
        @DefaultValue("https://api.openai.com/v1") String openaiBaseUrl,
        @DefaultValue("15") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {
}
