package com.misterworldtour.speech;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 음성 예약 대화 상대 (설계 이후 추가). 고객 말 한 마디에 다음 대사와 지금까지 모은 값을 돌려준다.
 * 값의 검사·신청은 {@link VoiceBookingManager} 가 하므로 구현은 대사와 값 추출만 맡는다.
 * 구현: {@link ScriptedDialogClient}(규칙, 기본·테스트·장애 대비), {@link OpenAIDialogClient}(LLM).
 */
public interface VoiceDialogClient {

    /** ASK: 계속 묻는다. BOOK: 고객이 최종 확인했으니 신청한다. */
    enum Action { ASK, BOOK }

    /**
     * 대화로 모은 값. 코드 값은 enum 이름(theme=HONEYMOON 등), 비어 있으면 null.
     *
     * @param departureDate 고객이 원하는 출발일 (yyyy-MM-dd)
     * @param pending       규칙 대화가 직전에 무엇을 물었는지 (LLM 은 쓰지 않음)
     */
    record DialogSlots(String theme, String tourLevel, Long productId, String departureDate, Integer travelerCount,
                       String hotelLevel, String meal, List<String> drinks, String pending) implements Serializable {

        public static final DialogSlots EMPTY = new DialogSlots(null, null, null, null, null, null, null, List.of(), null);

        public DialogSlots {
            drinks = drinks == null ? List.of() : drinks.stream().distinct().sorted().toList();
        }

        public DialogSlots withPending(String value) {
            return new DialogSlots(theme, tourLevel, productId, departureDate, travelerCount, hotelLevel, meal, drinks, value);
        }

        /** pending 을 뺀 값이 같은지 (최종 확인 뒤에 값이 바뀌었는지 볼 때). */
        public boolean sameChoice(DialogSlots o) {
            return withPending(null).equals(o.withPending(null));
        }
    }

    /** 대화 한 줄. role 은 customer 또는 system. */
    record Turn(String role, String text) implements Serializable {
        public static final String CUSTOMER = "customer";
        public static final String SYSTEM = "system";
    }

    /** 고객이 지금 신청할 수 있는 상품 (LLM 에 그대로 보여 준다). remainingSeats 는 허니문이면 -1. */
    record ProductInfo(long productId, String name, String destination, String description, String theme,
                       String themeLabel, String tourLevel, String levelLabel, LocalDate departureDate, int travelPeriod,
                       int basePrice, int remainingSeats) {
    }

    /** history 는 이번 말(utterance) 앞까지의 대화. */
    record DialogRequest(String customerName, LocalDate today, List<ProductInfo> products, DialogSlots slots,
                         List<Turn> history, String utterance) {
    }

    record DialogReply(String reply, DialogSlots slots, Action action) {
    }

    DialogReply reply(DialogRequest request);
}
