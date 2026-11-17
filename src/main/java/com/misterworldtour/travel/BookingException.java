package com.misterworldtour.travel;

import java.util.Map;

/** 신청할 수 없는 사유. errors 는 화면에 항목별로 보여 줄 안내. */
public class BookingException extends RuntimeException {

    private final transient Map<String, String> errors;

    public BookingException(String message) {
        this(message, Map.of());
    }

    public BookingException(String message, Map<String, String> errors) {
        super(message);
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
