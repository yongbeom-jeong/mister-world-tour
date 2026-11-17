package com.misterworldtour;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트마다 표를 비운다 (외래 키 순서대로). 표를 추가하면 여기에도 넣는다. */
public final class TestDb {

    private static final String[] TABLES = {
            "traveler", "travel_application", "travel_history", "travel_product", "member",
    };

    private TestDb() {
    }

    public static void clean(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
    }
}
