package com.misterworldtour.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 문자 설정 (application.yml 의 mwt.sms.*, 값은 .env 의 SMS_*·SOLAPI_*).
 *
 * @param client          SMS_CLIENT: Console(기본, 로그만) / FlakyDemo(시연용, 번호마다 2번 실패 후 성공) / Solapi(실제 발송).
 *                        클래스 이름(ConsoleSMSClient 등)으로 써도 된다.
 * @param timeout         SMS_TIMEOUT: 외부 호출 1회 대기 (초)
 * @param batchTimeLimit  SMS_BATCH_TIME_LIMIT: 신청 1건의 문자 묶음 전체 시간 한도 (초, 서버 요청 시간 한도보다 작게)
 * @param allowedNumbers  SMS_ALLOWED_NUMBERS: 있으면 이 번호로만 실제 발송
 */
@ConfigurationProperties(prefix = "mwt.sms")
public record SMSProperties(
        @DefaultValue("Console") String client,
        @DefaultValue("") String solapiApiKey,
        @DefaultValue("") String solapiApiSecret,
        @DefaultValue("") String solapiSender,
        @DefaultValue("10") @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
        @DefaultValue("40") @DurationUnit(ChronoUnit.SECONDS) Duration batchTimeLimit,
        @DefaultValue("") List<String> allowedNumbers) {

    public List<String> allowedNumbers() {
        return allowedNumbers == null ? List.of()
                : allowedNumbers.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
