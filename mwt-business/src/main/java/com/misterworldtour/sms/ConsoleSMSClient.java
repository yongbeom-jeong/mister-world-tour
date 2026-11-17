package com.misterworldtour.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 개발용: 실제로 보내지 않고 로그(실행 창)에 남긴다. 항상 성공. */
public class ConsoleSMSClient implements SMSServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSMSClient.class);

    /** 테스트 확인용: 보낸 (번호, 문구). */
    public static final List<String[]> SENT = new CopyOnWriteArrayList<>();

    @Override
    public String sendSMS(String contact, String message) {
        log.info("[SMS 가짜 발송] to={} msg={}", contact, message);
        SENT.add(new String[]{contact, message});
        return "";
    }
}
