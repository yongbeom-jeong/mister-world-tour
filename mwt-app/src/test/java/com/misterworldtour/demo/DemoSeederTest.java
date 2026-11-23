package com.misterworldtour.demo;

import com.misterworldtour.TestDb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 데모 데이터: 여러 번 실행해도 중복 없음. */
@SpringBootTest(properties = "mwt.media-root=target/test-media")
@ActiveProfiles("test")
class DemoSeederTest {

    @Autowired
    DemoSeeder seeder;
    @Autowired
    JdbcTemplate jdbc;

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void seedIsIdempotent() throws Exception {
        TestDb.clean(jdbc);
        seeder.seed();
        int members = count("member");
        int products = count("travel_product");
        int apps = count("travel_application");
        seeder.seed();
        assertThat(count("member")).isEqualTo(members).isEqualTo(4);
        assertThat(count("travel_product")).isEqualTo(products).isEqualTo(13);
        assertThat(count("travel_application")).isEqualTo(apps).isEqualTo(2);
        assertThat(count("travel_history")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM souvenir WHERE name = '건강 인삼'", Integer.class))
                .isEqualTo(30); // 확정된 효도 8명은 직원이 출발 처리할 때 차감
        assertThat(jdbc.queryForObject("SELECT departure_status FROM travel_product WHERE name = '다낭 골프 챌린지'",
                String.class)).isEqualTo("PENDING");
        TestDb.clean(jdbc);
    }
}
