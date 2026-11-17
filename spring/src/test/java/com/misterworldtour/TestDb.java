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
        // 기념품은 마이그레이션이 넣은 기본 4개(id 1~4)만 남기고 이름·테마·재고·사용 여부를 처음으로 되돌린다
        jdbc.update("DELETE FROM souvenir WHERE id > 4");
        String[][] initial = {{"1", "커플 기념티", "HONEYMOON"}, {"2", "건강 인삼", "FILIAL"},
                {"3", "골프공", "GOLF"}, {"4", "기념품 스카프", "TREKKING"}};
        for (String[] s : initial) {
            jdbc.update("UPDATE souvenir SET name = ?, name_key = ?, theme = ?, stock_quantity = 0, is_active = TRUE "
                    + "WHERE id = ?", s[1], s[1], s[2], Long.parseLong(s[0]));
        }
    }

    /** 테마 기본 기념품의 재고를 정한다 (테마 이름: HONEYMOON 등). */
    public static void setStock(JdbcTemplate jdbc, String theme, int quantity) {
        jdbc.update("UPDATE souvenir SET stock_quantity = ? WHERE theme = ?", quantity, theme);
    }
}
