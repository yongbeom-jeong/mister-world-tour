package com.misterworldtour.sms;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/** SMS_CLIENT 설정으로 문자 클라이언트를 고른다. 테스트는 .env 와 무관하게 Console 로 고정한다. */
@Configuration
class SmsConfig {

    @Bean
    SmsClient smsClient(SmsProperties p) {
        String name = p.client() == null ? "" : p.client().toLowerCase(Locale.ROOT);
        if (name.contains("solapi")) {
            return new SolapiSmsClient(p.solapiApiKey(), p.solapiApiSecret(), p.solapiSender(), p.timeout(),
                    p.allowedNumbers());
        }
        if (name.contains("flaky")) {
            return new FlakyDemoSmsClient();
        }
        return new ConsoleSmsClient();
    }
}
