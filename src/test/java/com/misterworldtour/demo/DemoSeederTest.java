package com.misterworldtour.demo;

import com.misterworldtour.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 데모 데이터: 여러 번 실행해도 중복 없음 (Django 판 SeedDemoTests·SeedWithApplicationsTests). */
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
        assertThat(count("travel_product")).isEqualTo(products).isEqualTo(12);
        assertThat(count("travel_application")).isEqualTo(apps).isEqualTo(2);
        assertThat(count("travel_history")).isEqualTo(7);
        // 제주 효도 8명 → 확정되며 건강 인삼 8개 차감 (30 → 22), 다낭 골프 2명은 미확정
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM souvenir WHERE name = '건강 인삼'", Integer.class))
                .isEqualTo(22);
        assertThat(jdbc.queryForObject("SELECT departure_status FROM travel_product WHERE name = '다낭 골프 챌린지'",
                String.class)).isEqualTo("PENDING");
        TestDb.clean(jdbc);
    }
}
