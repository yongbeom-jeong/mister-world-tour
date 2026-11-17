package com.misterworldtour;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 미스터 월드 투어 (Spring Boot 이식판).
 *
 * <p>패키지는 Django 판의 앱(=설계 서브시스템)과 1:1 로 대응한다.
 * members ↔ Member Management, travel ↔ Travel Management, souvenir ↔ Souvenir Management,
 * speech ↔ Speech Recognition Integration, sms ↔ SMS Notification. 공통 화면·설정은 common.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MwtApplication {

    public static void main(String[] args) {
        SpringApplication.run(MwtApplication.class, args);
    }
}
