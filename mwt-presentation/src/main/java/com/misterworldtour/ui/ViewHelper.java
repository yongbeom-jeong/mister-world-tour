package com.misterworldtour.ui;

import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelProduct;

import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * 템플릿 도구. 템플릿에서 {@code ${@mwt.won(값)}} 처럼 쓴다.
 */
@Component("mwt")
public class ViewHelper {

    /** 1234000 → "1,234,000원". */
    public String won(Number value) {
        return value == null ? "" : NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원";
    }

    /** 1234000 → "1,234,000". */
    public String number(Number value) {
        return value == null ? "" : NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
    }

    /** 상품 사진: 직원이 올린 사진, 없으면 테마 기본 사진. small 이면 800px 판. */
    public String productPhoto(TravelProduct product, boolean small) {
        return Display.productPhoto(product, small);
    }

    /** 테마 화면 정보 (아이콘·소개 문구 등). */
    public Display.ThemeView theme(Theme theme) {
        return theme == null ? null : Display.theme(theme);
    }

    /** 날짜 형식. 예: date(d, "n월 j일") 대신 Java 형식 "M월 d일". */
    public String date(TemporalAccessor value, String pattern) {
        return value == null ? "" : DateTimeFormatter.ofPattern(pattern, Locale.KOREA).format(value);
    }

    public LocalDate today() {
        return LocalDate.now();
    }
}
