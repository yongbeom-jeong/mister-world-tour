package com.misterworldtour.speech;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.speech.SpeechServiceClient.Recognized;
import com.misterworldtour.speech.SpeechServiceClient.VoiceInput;
import com.misterworldtour.speech.VoiceDialogClient.Action;
import com.misterworldtour.speech.VoiceDialogClient.DialogReply;
import com.misterworldtour.speech.VoiceDialogClient.DialogRequest;
import com.misterworldtour.speech.VoiceDialogClient.DialogSlots;
import com.misterworldtour.speech.VoiceDialogClient.ProductInfo;
import com.misterworldtour.speech.VoiceDialogClient.Turn;
import com.misterworldtour.travel.BookingException;
import com.misterworldtour.travel.DrinkOption;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;
import com.misterworldtour.travel.TravelManagementAPI.ConfigurationException;
import com.misterworldtour.travel.TravelManagementAPI.ConfigurationResult;
import com.misterworldtour.travel.TravelPriceCalculator;
import com.misterworldtour.travel.TravelProduct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 음성 예약 처리 (설계 이후 추가). 대화 상대({@link VoiceDialogClient})는 대사와 값만 내고,
 * 여기서 값을 상품 목록·구성 규칙으로 다시 검사한 뒤 고객이 확인한 경우에만 신청한다.
 * LLM 호출이 실패한 턴은 규칙 대화({@link ScriptedDialogClient})로 답한다.
 */
@Service
public class VoiceBookingManager implements VoiceBookingAPI {

    private static final Logger log = LoggerFactory.getLogger(VoiceBookingManager.class);

    static final int MAX_HISTORY = 40;

    private final TravelManagementAPI travel;
    private final SpeechServiceClient speech;
    private final VoiceDialogClient client;
    private final ScriptedDialogClient scripted = new ScriptedDialogClient();
    private final Clock clock;

    VoiceBookingManager(TravelManagementAPI travel, SpeechServiceClient speech, VoiceDialogClient client, Clock clock) {
        this.travel = travel;
        this.speech = speech;
        this.client = client;
        this.clock = clock;
    }

    private static long currentMemberId() {
        return LoginSession.current().filter(LoginSession::isCustomer).map(LoginSession::getMemberId)
                .orElseThrow(() -> new IllegalStateException("고객 로그인이 필요합니다."));
    }

    @Override
    public VoiceDialogTurn startVoiceBooking() {
        MemberManagementAPI.Contact me = travel.applicantContact(currentMemberId());
        String greeting = VoiceDialogText.greeting(me.name());
        VoiceDialogState state = new VoiceDialogState(List.of(new Turn(Turn.SYSTEM, greeting)), DialogSlots.EMPTY, null);
        return new VoiceDialogTurn(state, greeting, false, null, Map.of(), false);
    }

    @Override
    public VoiceDialogTurn talk(VoiceDialogState state, String transcript) {
        if (state == null) {
            return startVoiceBooking();
        }
        if (state.applicationId() != null) {
            return new VoiceDialogTurn(state, "예약은 이미 끝났어요. 새로 예약하시려면 '처음부터'를 눌러 주세요.", true,
                    state.applicationId(), Map.of(), false);
        }
        Recognized recognized = speech.recognize(VoiceInput.ofTranscript(transcript));
        List<ProductInfo> products = productInfos();
        if (!recognized.ok()) {
            return new VoiceDialogTurn(state, recognized.error() + " 다시 말씀해 주세요.", false, null,
                    selection(state.slots(), products), false);
        }
        long memberId = currentMemberId();
        String name = travel.applicantContact(memberId).name();
        DialogRequest request = new DialogRequest(name, LocalDate.now(clock), products, state.slots(), state.history(),
                recognized.text());

        DialogReply out;
        boolean fallback = false;
        try {
            out = client.reply(request);
        } catch (RuntimeException e) {
            log.warn("음성 예약 대화 상대 호출 실패, 이번 턴은 규칙 대화로 답함: {}", e.getMessage());
            out = scripted.reply(request);
            fallback = true;
        }
        Checked checked = check(out, state.slots(), products, name);
        log.info("음성 예약 턴 member={} action={} fallback={} product={}", memberId, checked.action(), fallback,
                checked.slots().productId());

        if (checked.action() == Action.BOOK) {
            return book(state, recognized.text(), checked, products, memberId, fallback);
        }
        VoiceDialogState next = append(state, recognized.text(), checked.reply(), checked.slots(), null);
        return new VoiceDialogTurn(next, checked.reply(), false, null, selection(checked.slots(), products), fallback);
    }

    record Checked(String reply, DialogSlots slots, Action action, ProductInfo product) {
    }

    /** 대화 상대가 낸 값을 실제 상품·구성 규칙으로 다시 검사한다. 안 되는 값은 직전 값으로 되돌리고 다시 묻는다. */
    Checked check(DialogReply out, DialogSlots before, List<ProductInfo> products, String customerName) {
        DialogSlots s = out.slots() == null ? before : out.slots();
        List<String> problems = new ArrayList<>();

        Theme theme = Theme.parse(s.theme());
        TourLevel level = TourLevel.parse(s.tourLevel());
        ProductInfo product = s.productId() == null ? null
                : products.stream().filter(p -> p.productId() == s.productId()).findFirst().orElse(null);
        if (s.productId() != null && product == null) {
            problems.add("말씀하신 상품은 지금 신청할 수 없어요.");
        }
        if (product != null) {
            theme = Theme.parse(product.theme());
            level = TourLevel.parse(product.tourLevel());
        }
        Integer count = s.travelerCount();
        if (product != null && product.remainingSeats() < 0) {
            count = 2;
        } else if (product != null && count != null && (count < 1 || count > product.remainingSeats())) {
            problems.add("남은 자리는 " + product.remainingSeats() + "명이에요. 인원을 다시 말씀해 주세요.");
            count = null;
        }
        String date = s.departureDate();
        try {
            date = date == null ? null : LocalDate.parse(date).toString();
        } catch (DateTimeParseException e) {
            date = before.departureDate();
        }
        String hotel = s.hotelLevel();
        String meal = s.meal();
        List<String> drinks = s.drinks().stream()
                .filter(d -> java.util.Arrays.stream(DrinkOption.values()).anyMatch(o -> o.name().equals(d))).toList();
        if (product != null) {
            try {
                travel.configureTravelProduct(product.productId(), null, changes(hotel, meal, drinks));
            } catch (ConfigurationException e) {
                problems.add(String.join(" ", e.getErrors().values()));
                hotel = before.hotelLevel();
                meal = before.meal();
                drinks = before.drinks();
            }
        }
        DialogSlots slots = new DialogSlots(theme == null ? null : theme.name(), level == null ? null : level.name(),
                product == null ? null : product.productId(), date, count, hotel, meal, drinks, s.pending());

        String reply = spokenDates(out.reply() == null ? "" : out.reply().strip());
        Action action = out.action();
        if (!problems.isEmpty()) {
            return new Checked("죄송해요, " + String.join(" ", problems), slots, Action.ASK, product);
        }
        if (action == Action.BOOK) {
            if (product == null) {
                return new Checked("어떤 여행으로 예약할지 먼저 정해 주세요.", slots, Action.ASK, null);
            }
            if (count == null) {
                return new Checked("몇 분이 가시나요?", slots, Action.ASK, product);
            }
            if (!slots.sameChoice(before)) { // 고객이 마지막으로 들은 내용과 다르면 다시 확인
                return new Checked(VoiceDialogText.selection(customerName, product, slots) + " 이대로 예약할까요?",
                        slots.withPending(ScriptedDialogClient.P_CONFIRM), Action.ASK, product);
            }
        }
        if (reply.isEmpty() && action == Action.ASK) {
            reply = "다시 한 번 말씀해 주시겠어요?";
        }
        return new Checked(reply, slots, action, product);
    }

    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("\\d{4}-(\\d{2})-(\\d{2})");

    /** LLM 이 대사에 2026-12-24 처럼 쓴 날짜를 읽기 좋게 "12월 24일"로 바꾼다. */
    static String spokenDates(String reply) {
        return ISO_DATE.matcher(reply).replaceAll(m -> Integer.parseInt(m.group(1)) + "월 " + Integer.parseInt(m.group(2)) + "일");
    }

    private static Map<String, Object> changes(String hotel, String meal, List<String> drinks) {
        Map<String, Object> m = new HashMap<>();
        m.put(TravelConfiguration.HOTEL, hotel);
        m.put(TravelConfiguration.MEAL, meal);
        m.put(TravelConfiguration.DRINKS, drinks.isEmpty() ? null : drinks);
        return m;
    }

    /** 화면 신청과 같은 검토 → 등록. 동반자는 자리 이름으로 넣는다. */
    private VoiceDialogTurn book(VoiceDialogState state, String said, Checked checked, List<ProductInfo> products,
                                 long memberId, boolean fallback) {
        DialogSlots s = checked.slots();
        ProductInfo product = checked.product();
        ApplicationReviewResult review = null;
        String reply;
        Long applicationId = null;
        try {
            TravelConfiguration config = travel.configureTravelProduct(product.productId(), null,
                    changes(s.hotelLevel(), s.meal(), s.drinks())).configuration();
            MemberManagementAPI.Contact me = travel.applicantContact(memberId);
            List<TravelerInput> travelers = new ArrayList<>();
            travelers.add(new TravelerInput(me.name(), me.contact(), true));
            // 음성으로는 동반자 이름을 받지 않는다 (인식이 부정확). 비우면 신청 규칙대로 '동반자 N'으로 저장된다
            for (int i = 1; i < s.travelerCount(); i++) {
                travelers.add(new TravelerInput("", "", false));
            }
            review = travel.reviewApplication(product.productId(), config, travelers);
            ApplicationResult result = travel.submitTravelApplication(product.productId(), config, travelers,
                    review.requestKey(), review.price());
            applicationId = result.applicationId();
            reply = VoiceDialogText.booked(product.departureDate());
            log.info("음성 예약 신청 완료 member={} application={} product={}", memberId, applicationId, product.productId());
        } catch (BookingException | ConfigurationException e) {
            reply = "죄송해요, 예약하지 못했어요. " + e.getMessage();
            if (e instanceof BookingException b && !b.getErrors().isEmpty()) {
                reply += " " + String.join(" ", b.getErrors().values());
            }
        } catch (RuntimeException e) {
            log.error("음성 예약 신청 중 오류 member={} product={}", memberId, product.productId(), e);
            Optional<Long> saved = Optional.empty();
            if (review != null) {
                try {
                    saved = travel.findSubmittedApplication(memberId, review.requestKey());
                } catch (RuntimeException again) {
                    log.error("음성 예약 반영 여부 확인 실패 member={}", memberId, again);
                }
            }
            if (saved.isPresent()) {
                applicationId = saved.get();
                reply = VoiceDialogText.booked(product.departureDate());
            } else {
                reply = "일시적인 오류로 예약을 마치지 못했어요. '내 신청'에서 확인하시고, 없으면 다시 말씀해 주세요.";
            }
        }
        VoiceDialogState next = append(state, said, reply, s.withPending(applicationId == null
                ? ScriptedDialogClient.P_CONFIRM : null), applicationId);
        return new VoiceDialogTurn(next, reply, applicationId != null, applicationId, selection(s, products), fallback);
    }

    private static VoiceDialogState append(VoiceDialogState state, String said, String reply, DialogSlots slots,
                                           Long applicationId) {
        List<Turn> history = new ArrayList<>(state.history());
        history.add(new Turn(Turn.CUSTOMER, said));
        history.add(new Turn(Turn.SYSTEM, reply));
        while (history.size() > MAX_HISTORY) {
            history.remove(1); // 첫 인사는 남긴다
        }
        return new VoiceDialogState(history, slots, applicationId);
    }

    /** 고객이 지금 신청할 수 있는 상품 (출발 전, 자리가 남은 것). */
    List<ProductInfo> productInfos() {
        List<ProductInfo> out = new ArrayList<>();
        for (TravelProduct p : travel.loadProducts(null, null)) {
            // 허니문은 정원 없이 2인 신청마다 확정 → -1 로 표시 (대화에서 인원을 묻지 않음)
            int remaining = p.isHoneymoon() ? -1 : travel.remainingSeats(p);
            if (remaining == 0) {
                continue;
            }
            out.add(new ProductInfo(p.getProductId(), p.getProductName(), p.getDestination(),
                    p.getDescription() == null ? "" : p.getDescription(), p.getTheme().name(),
                    p.getTheme().getLabel(), p.getTourLevel().name(), p.getTourLevel().getLabel(), p.getDepartureDate(),
                    p.getTravelPeriod(), p.getBasePrice(), remaining));
        }
        return out;
    }

    /** 화면 옆 '고른 내용' 표시. */
    private Map<String, String> selection(DialogSlots s, List<ProductInfo> products) {
        Map<String, String> m = new LinkedHashMap<>();
        Theme theme = Theme.parse(s.theme());
        TourLevel level = TourLevel.parse(s.tourLevel());
        if (theme != null) {
            m.put("테마", theme.getLabel());
        }
        if (level != null) {
            m.put("스타일", level.getLabel());
        }
        if (s.departureDate() != null) {
            m.put("원하는 출발일", VoiceDialogText.date(LocalDate.parse(s.departureDate())));
        }
        ProductInfo product = s.productId() == null ? null
                : products.stream().filter(p -> p.productId() == s.productId()).findFirst().orElse(null);
        if (product == null) {
            return m;
        }
        m.put("상품", product.name());
        m.put("출발일", VoiceDialogText.date(product.departureDate()) + " (" + product.travelPeriod() + "일)");
        if (s.travelerCount() != null) {
            m.put("인원", s.travelerCount() + "명");
        }
        try {
            ConfigurationResult c = travel.configureTravelProduct(product.productId(), null,
                    changes(s.hotelLevel(), s.meal(), s.drinks()));
            Map<String, String> described = c.configuration().describe();
            m.put("호텔", described.get(TravelConfiguration.HOTEL));
            m.put("식사", described.get(TravelConfiguration.MEAL));
            m.put("음료", described.get(TravelConfiguration.DRINKS));
            m.put("1인 금액", "%,d원".formatted(c.unitPrice()));
            int drinkPrice = TravelPriceCalculator.calculateDrinkPrice(c.configuration());
            if (drinkPrice > 0) {
                m.put("음료 금액", "%,d원 (신청 전체)".formatted(drinkPrice));
            }
        } catch (ConfigurationException e) {
            // 검사에서 이미 걸러져 여기 오지 않는다
        }
        return m;
    }
}
