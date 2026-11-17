package com.misterworldtour.travel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 다녀온 여행을 한꺼번에 완료 이력으로 만든다.
 * 이력 조회·신청 금액 계산 직전에도 그 고객 것을 반영하므로, 이 명령은 일괄 정리용이다.
 *
 * <pre>mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--complete-trips</pre>
 */
@Component
class CompleteTripsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompleteTripsRunner.class);

    private final TravelManager travel;
    private final ConfigurableApplicationContext context;

    CompleteTripsRunner(TravelManager travel, ConfigurableApplicationContext context) {
        this.travel = travel;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("complete-trips")) {
            return;
        }
        int created = travel.completeFinishedTrips(null);
        log.info("완료 여행 이력 {}건을 만들었습니다.", created);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
