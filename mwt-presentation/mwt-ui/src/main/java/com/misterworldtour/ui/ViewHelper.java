package com.misterworldtour.ui;

import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelProduct;

import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/** 템플릿 도구: {@code ${@mwt.won(값)}} */
@Component("mwt")
public class ViewHelper {

    /** 1234000 → "1,234,000원". */
    public String won(Number value) {
        return value == null ? "" : NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원";
    }

    public String number(Number value) {
        return value == null ? "" : NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
    }

    /** small 이면 800px 판. */
    public String productPhoto(TravelProduct product, boolean small) {
        return Display.productPhoto(product, small);
    }

    public Display.ThemeView theme(Theme theme) {
        return theme == null ? null : Display.theme(theme);
    }

    public String date(TemporalAccessor value, String pattern) {
        return value == null ? "" : DateTimeFormatter.ofPattern(pattern, Locale.KOREA).format(value);
    }

    public LocalDate today() {
        return LocalDate.now();
    }
}
