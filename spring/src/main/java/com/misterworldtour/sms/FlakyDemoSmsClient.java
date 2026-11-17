package com.misterworldtour.sms;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시연용: 번호마다 처음 2번은 실패하고 3번째에 성공한다 (재시도 동작 확인용).
 * .env 에 {@code SMS_CLIENT=FlakyDemoSMSClient} 로 설정한다.
 */
public class FlakyDemoSmsClient extends ConsoleSmsClient {

    private static final Map<String, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    @Override
    public String send(String contact, String message) {
        int n = ATTEMPTS.merge(contact, 1, Integer::sum);
        if (n % 3 != 0) {
            throw new SendException("시연용 실패 (" + n + "번째 시도)");
        }
        return super.send(contact, message);
    }
}
