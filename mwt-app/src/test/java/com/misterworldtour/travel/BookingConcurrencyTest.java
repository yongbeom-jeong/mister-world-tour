package com.misterworldtour.travel;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 신청. 상품 행 잠금으로 정원·재고가 넘지 않는지.
 * H2 는 행 잠금을 지키므로 개발 PC 에서도 확인할 수 있다.
 */
class BookingConcurrencyTest extends BookingTestBase {

    @Test
    void capacityAndStockNeverExceeded() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        jdbc.update("UPDATE souvenir SET stock_quantity = 10 WHERE name = '골프공'");
        int customers = 5; // 각 3명, 정원 10명 → 최대 3건 성공
        List<ApplicationReviewResult> reviews = new ArrayList<>();
        List<LoginSession> members = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            LoginSession m = customer("c" + i);
            members.add(m);
            reviews.add(review(m, p, "a" + i, "b" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(customers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    booking.submitTravelApplication(members.get(n).getMemberId(), reviews.get(n));
                    return true;
                } catch (BookingException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            ok += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(3);
        assertThat(booking.currentTravelerCount(p.getProductId())).isEqualTo(9);
        assertThat(stock("골프공")).isEqualTo(10); // 차감은 직원 출발 처리 때
        booking.departTrip(p.getProductId());
        assertThat(stock("골프공")).isEqualTo(1);
    }
}
