package com.misterworldtour.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 문자 연동 확인용 시험 발송 (Django 판 {@code manage.py send_test_sms}). .env 의 SMS_CLIENT 를 그대로 쓴다 (재시도 없이 1회).
 *
 * <pre>mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--send-test-sms=010-1234-5678 --text=보낼 내용"</pre>
 *
 * 보낸 뒤 서버를 끄고 종료 코드로 결과를 알린다 (0 성공, 1 실패, 2 결과 불명).
 */
@Component
class SendTestSmsRunner implements ApplicationRunner {

    static final String DEFAULT_TEXT = "[미스터 월드 투어] 문자 발송 시험입니다.";
    private static final Logger log = LoggerFactory.getLogger(SendTestSmsRunner.class);

    private final SmsService sms;
    private final ConfigurableApplicationContext context;

    SendTestSmsRunner(SmsService sms, ConfigurableApplicationContext context) {
        this.sms = sms;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> to = args.getOptionValues("send-test-sms");
        if (to == null || to.isEmpty()) {
            return;
        }
        List<String> texts = args.getOptionValues("text");
        int code = send(to.get(0), texts == null || texts.isEmpty() ? DEFAULT_TEXT : texts.get(0));
        System.exit(SpringApplication.exit(context, () -> code));
    }

    int send(String contact, String text) {
        log.info("클라이언트: {} → {}", sms.clientName(), contact);
        try {
            sms.sendTest(contact, text);
            log.info("발송 요청 성공");
            return 0;
        } catch (SmsClient.UnknownResultException e) {
            log.error("결과 불명: {} (휴대폰에서 수신 여부를 확인하세요)", e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            log.error("발송 실패: {}", e.getMessage());
            return 1;
        }
    }
}
