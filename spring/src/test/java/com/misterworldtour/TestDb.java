package com.misterworldtour;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트마다 표를 비운다 (외래 키 순서대로). 표를 추가하면 여기에도 넣는다. */
public final class TestDb {

    private static final String[] TABLES = {
            "stock_issue", "stock_receipt",
            "traveler", "travel_application", "travel_history", "travel_product", "member",
    };

    private TestDb() {
    }

    public static void clean(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        // 기념품은 마이그레이션이 넣은 기본 4개(id 1~4)만 남기고 재고 0, 사용 중으로 되돌린다
        jdbc.update("DELETE FROM souvenir WHERE id > 4");
        jdbc.update("UPDATE souvenir SET stock_quantity = 0, is_active = TRUE");
    }

    /** 테마 기본 기념품의 재고를 정한다 (테마 이름: HONEYMOON 등). */
    public static void setStock(JdbcTemplate jdbc, String theme, int quantity) {
        jdbc.update("UPDATE souvenir SET stock_quantity = ? WHERE theme = ?", quantity, theme);
    }
}
