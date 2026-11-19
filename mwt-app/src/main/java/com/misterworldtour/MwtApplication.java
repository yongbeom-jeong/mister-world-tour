package com.misterworldtour;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 미스터 월드 투어 실행 모듈. 설계 서브시스템마다 패키지·모듈(jar)이 하나이고 여기서 모두 묶는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MwtApplication {

    /** 업무 날짜 기준 시계. 테스트에서는 고정 시계로 바꾼다. */
    @Bean
    Clock clock(@Value("${mwt.time-zone:Asia/Seoul}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }

    public static void main(String[] args) {
        SpringApplication.run(MwtApplication.class, args);
    }
}
