package com.misterworldtour;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 미스터 월드 투어 (Spring Boot 이식판).
 *
 * <p>패키지는 설계 서브시스템과 1:1 로 대응한다: ui(User Interface), members(Member Management),
 * travel(Travel Management), souvenir(Souvenir Management), dataaccess(Data Access),
 * speech(Speech Recognition Integration), sms(SMS Notification). 패키지마다 모듈(jar)이 하나이고,
 * 이 실행 모듈이 모두 묶는다. 설정 값은 쓰는 패키지가 가진다 (UiProperties, TravelProperties, SMSProperties).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MwtApplication {

    /** 업무 날짜(출발일·완료일 판정)의 기준 시계. 테스트에서는 고정 시계로 바꾼다. */
    @Bean
    Clock clock(@Value("${mwt.time-zone:Asia/Seoul}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }

    public static void main(String[] args) {
        SpringApplication.run(MwtApplication.class, args);
    }
}
