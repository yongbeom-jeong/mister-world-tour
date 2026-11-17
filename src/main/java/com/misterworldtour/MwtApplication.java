package com.misterworldtour;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 미스터 월드 투어 (Spring Boot 이식판).
 *
 * <p>패키지는 설계 서브시스템과 1:1 로 대응한다: ui(User Interface), members(Member Management),
 * travel(Travel Management), souvenir(Souvenir Management), dataaccess(Data Access),
 * speech(Speech Recognition Integration), sms(SMS Notification). common 은 보안·웹 설정.
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
