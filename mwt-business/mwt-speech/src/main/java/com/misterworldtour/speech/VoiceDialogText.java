package com.misterworldtour.speech;

import com.misterworldtour.speech.VoiceDialogClient.DialogSlots;
import com.misterworldtour.speech.VoiceDialogClient.ProductInfo;
import com.misterworldtour.travel.DrinkOption;
import com.misterworldtour.travel.HotelLevel;
import com.misterworldtour.travel.MealOption;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 음성 예약 대사 문구 (시나리오 말투). 규칙 대화와 서버 확인 문구가 함께 쓴다. */
final class VoiceDialogText {

    /** 말로 부를 음료 이름. 순서도 시나리오(커피, 샴페인) 순. 단위는 DrinkOption 의 것(포트·병·세트). */
    static final Map<String, String> DRINK_NAMES = new LinkedHashMap<>();

    static {
        DRINK_NAMES.put("COFFEE", "커피");
        DRINK_NAMES.put("CHAMPAGNE", "샴페인");
        DRINK_NAMES.put("WINE", "와인");
        DRINK_NAMES.put("BEER", "수제맥주");
    }

    private VoiceDialogText() {
    }

    static String greeting(String customerName) {
        return "안녕하세요, " + customerName + " 고객님, 어떤 여행 상품을 예약하시겠습니까?";
    }

    static String date(LocalDate d) {
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
    }

    /** "커피 1포트, 샴페인 1병". */
    static String drinks(List<String> codes) {
        List<String> out = new ArrayList<>();
        DRINK_NAMES.forEach((code, name) -> {
            int q = java.util.Collections.frequency(codes, code);
            if (q > 0) {
                out.add(name + " " + q + DrinkOption.valueOf(code).getUnit());
            }
        });
        return String.join(", ", out);
    }

    /** 시나리오: "네, OOO 고객님, 여행은 허니문 낭만 투어, 스타일은 프리미엄 스타일로 예약하셨습니다." */
    static String selection(String customerName, ProductInfo p, DialogSlots s) {
        List<String> parts = new ArrayList<>();
        parts.add("여행은 " + p.themeLabel() + "(" + p.name() + ", " + date(p.departureDate()) + " 출발)");
        if (p.remainingSeats() >= 0 && s.travelerCount() != null) {
            parts.add(s.travelerCount() + "명");
        }
        parts.add("스타일은 " + p.levelLabel() + " 스타일");
        if (s.hotelLevel() != null) {
            parts.add("호텔은 " + label(HotelLevel.class, s.hotelLevel()));
        }
        if (s.meal() != null) {
            parts.add("식사는 " + label(MealOption.class, s.meal()));
        }
        String head = "네, " + customerName + " 고객님, " + String.join(", ", parts);
        if (s.drinks().isEmpty()) {
            return head + ro(head) + " 예약하셨습니다.";
        }
        return head + ", " + drinks(s.drinks()) + " 추가하셨습니다.";
    }

    /** 조사 '로/으로' (받침이 있으면 '으로', ㄹ 받침과 받침 없음은 '로'). */
    static String ro(String word) {
        char last = word.isEmpty() ? ' ' : word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "로";
        }
        int jong = (last - 0xAC00) % 28;
        return jong == 0 || jong == 8 ? "로" : "으로";
    }

    static String booked(LocalDate departure) {
        return date(departure) + "에 선택하신대로 예약되었습니다. 감사합니다.";
    }

    private static <E extends Enum<E>> String label(Class<E> type, String code) {
        try {
            Enum<E> e = Enum.valueOf(type, code);
            return e instanceof HotelLevel h ? h.getLabel() : ((MealOption) e).getLabel();
        } catch (IllegalArgumentException ex) {
            return code;
        }
    }
}
