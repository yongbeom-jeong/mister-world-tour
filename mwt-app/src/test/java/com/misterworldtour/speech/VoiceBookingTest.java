package com.misterworldtour.speech;

import com.misterworldtour.TestDb;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.LoginSessionSupport;
import com.misterworldtour.members.Role;
import com.misterworldtour.speech.VoiceBookingAPI.VoiceDialogState;
import com.misterworldtour.speech.VoiceBookingAPI.VoiceDialogTurn;
import com.misterworldtour.speech.VoiceDialogClient.Action;
import com.misterworldtour.speech.VoiceDialogClient.DialogReply;
import com.misterworldtour.speech.VoiceDialogClient.DialogRequest;
import com.misterworldtour.speech.VoiceDialogClient.DialogSlots;
import com.misterworldtour.speech.VoiceDialogClient.ProductInfo;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelDataAccessAPI;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.travel.TravelProduct;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 음성 예약: 시나리오 대화(규칙), 서버 검사, 신청, OpenAI 요청·응답 형식, 화면. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceBookingTest {

    /** 최종발표 시나리오 2.1 의 고객 말. */
    static final List<String> SCENARIO = List.of("멋진 여행 상품 추천해주세요.", "다음달 오늘에 결혼해요",
            "허니문 낭만 투어 좋겠어요.", "네, 프리미엄 스타일 좋아요.", "그리고 식사시 커피 1포트, 샴페인 1병 추가해요.",
            "맞아요.", "없어요.");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LoginSessionSupport principals;
    @Autowired
    TravelDataAccessAPI dataAccess;
    @Autowired
    TravelManagementAPI travel;
    @Autowired
    SpeechServiceClient speech;
    @Autowired
    VoiceBookingManager manager;
    @Autowired
    Clock clock;

    LoginSession bride;
    TravelProduct guam;
    LocalDate today;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        jdbc.update("UPDATE souvenir SET stock_quantity = 100");
        today = LocalDate.now(clock);
        bride = principals.create("bride", Role.CUSTOMER, "박단골", "010-2000-0001");
        // 다음 달 오늘 출발 프리미엄 허니문과, 날짜가 먼 다른 허니문·트레킹
        guam = save("괌 투몬 선셋 허니문", Theme.HONEYMOON, TourLevel.PREMIUM, today.plusMonths(1));
        save("발리 우붓 로맨틱 허니문", Theme.HONEYMOON, TourLevel.GRAND, today.plusDays(10));
        save("몰디브 워터빌라 허니문", Theme.HONEYMOON, TourLevel.PREMIUM, today.plusDays(80));
        save("한라산·올레길 트레킹", Theme.TREKKING, TourLevel.CLASSIC, today.plusDays(15));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                bride, null, List.of(new SimpleGrantedAuthority(bride.getRole().authority()))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    TravelProduct save(String name, Theme theme, TourLevel level, LocalDate departure) {
        return dataAccess.saveProduct(new TravelProduct(name, "어딘가", "", theme, level, 2_000_000, 5, departure));
    }

    // ---------------------------------------------------------------- 규칙 대화가 시나리오를 그대로 따르는지

    @Test
    void scriptedDialogFollowsScenarioAndBooks() {
        VoiceDialogTurn turn = manager.startVoiceBooking();
        assertThat(turn.reply()).isEqualTo("안녕하세요, 박단골 고객님, 어떤 여행 상품을 예약하시겠습니까?");
        List<String> replies = new ArrayList<>();
        VoiceDialogState state = turn.state();
        for (String said : SCENARIO) {
            turn = manager.talk(state, said);
            state = turn.state();
            replies.add(turn.reply());
        }
        assertThat(replies.get(0)).isEqualTo("무슨 기념일인가요?");
        assertThat(replies.get(1)).isEqualTo("정말 축하드려요. 허니문 낭만 투어 또는 아웃도어 트레킹 투어는 어떠세요?");
        assertThat(replies.get(2)).isEqualTo("허니문 낭만 투어 알겠습니다. 그리고 스타일은 프리미엄 스타일 어떨까요?");
        // 상품 선택: 날짜가 딱 맞는 괌으로 정하고, 다른 프리미엄 허니문도 알려 준 뒤 구성 변경을 묻는다
        assertThat(replies.get(3)).startsWith("네, 박단골 고객님, 여행은 허니문 낭만 투어(괌 투몬 선셋 허니문")
                .contains("스타일은 프리미엄 스타일로 예약하셨습니다.").contains("다른 곳을 원하시면")
                .endsWith(ScriptedDialogClient.CONFIG_QUESTION);
        assertThat(replies.get(4)).endsWith("스타일은 프리미엄 스타일, 커피 1포트, 샴페인 1병 추가하셨습니다.");
        assertThat(replies.get(5)).isEqualTo("추가로 필요하신 것 있으세요?");
        LocalDate dep = today.plusMonths(1);
        assertThat(replies.get(6)).isEqualTo(dep.getMonthValue() + "월 " + dep.getDayOfMonth() + "일에 선택하신대로 예약되었습니다. 감사합니다.");

        assertThat(turn.done()).isTrue();
        assertThat(turn.applicationId()).isNotNull();
        Map<String, Object> app = jdbc.queryForMap("SELECT * FROM travel_application WHERE id = ?", turn.applicationId());
        assertThat(((Number) app.get("product_id")).longValue()).isEqualTo(guam.getProductId());
        assertThat(String.valueOf(app.get("extra_drinks"))).contains("COFFEE").contains("CHAMPAGNE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM traveler WHERE application_id = ?", Integer.class,
                turn.applicationId())).isEqualTo(2);
        // 허니문은 신청과 함께 출발 확정. 기념품은 직원이 출발 처리할 때 차감하므로 아직 그대로
        assertThat(jdbc.queryForObject("SELECT departure_status FROM travel_application WHERE id = ?", String.class,
                turn.applicationId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM souvenir WHERE name = '커플 기념티'", Integer.class))
                .isEqualTo(100);
        // 동반자 이름은 비워 넘기고 신청 규칙대로 '동반자 1'로 저장
        assertThat(jdbc.queryForList("SELECT name FROM traveler WHERE application_id = ? ORDER BY id", String.class,
                turn.applicationId())).containsExactly("박단골", "동반자 1");

        // 끝난 대화에 더 말해도 다시 신청하지 않는다
        VoiceDialogTurn again = manager.talk(state, "없어요");
        assertThat(again.done()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_application", Integer.class)).isEqualTo(1);
    }

    @Test
    void drinkQuantitiesAreHeardAndBooked() {
        VoiceDialogState state = manager.talk(manager.startVoiceBooking().state(), "괌 투몬 선셋 허니문으로 할게요").state();
        VoiceDialogTurn turn = manager.talk(state, "커피 2포트랑 샴페인 한 병 추가해 주세요");
        assertThat(turn.reply()).contains("커피 2포트, 샴페인 1병 추가하셨습니다.");
        assertThat(turn.selection()).containsKey("음료 금액");
        turn = manager.talk(turn.state(), "커피는 빼 주세요");
        assertThat(turn.reply()).contains("샴페인 1병").doesNotContain("커피");
        turn = manager.talk(turn.state(), "커피 3포트로 해 주세요");
        turn = manager.talk(turn.state(), "맞아요");
        turn = manager.talk(turn.state(), "없어요");
        assertThat(turn.done()).isTrue();
        assertThat(jdbc.queryForObject("SELECT extra_drinks FROM travel_application WHERE id = ?", String.class,
                turn.applicationId())).isEqualTo("CHAMPAGNE,COFFEE,COFFEE,COFFEE");
    }

    @Test
    void productIsChosenFromListLikeTheScreen() {
        VoiceDialogState state = manager.startVoiceBooking().state();
        VoiceDialogTurn turn = manager.talk(state, "허니문 프리미엄으로 보여 주세요");
        assertThat(turn.reply()).contains("어디로 가실래요?").contains("12월").doesNotContain("발리");  // 그랜드 상품은 빼고
        turn = manager.talk(turn.state(), "첫 번째로 할게요");
        assertThat(turn.state().slots().productId()).isEqualTo(guam.getProductId());
        assertThat(turn.reply()).endsWith(ScriptedDialogClient.CONFIG_QUESTION);
        turn = manager.talk(turn.state(), "호텔은 4성으로 바꿔 주세요");
        assertThat(turn.state().slots().hotelLevel()).isEqualTo("STAR4");
        assertThat(turn.reply()).contains("호텔은 4등급 호텔");
    }

    @Test
    void namingDestinationPicksThatProduct() {
        jdbc.update("UPDATE travel_product SET destination = '미국령 괌' WHERE id = ?", guam.getProductId());
        VoiceDialogTurn turn = manager.talk(manager.startVoiceBooking().state(), "괌으로 가고 싶어요");
        assertThat(turn.state().slots().productId()).isEqualTo(guam.getProductId());
        assertThat(turn.state().slots().theme()).isEqualTo("HONEYMOON");
        assertThat(turn.reply()).contains("괌 투몬 선셋 허니문");
    }

    @Test
    void groupTourAsksTravelerCountAndChecksSeats() {
        VoiceDialogState state = manager.startVoiceBooking().state();
        VoiceDialogTurn turn = manager.talk(state, "아웃도어 트레킹 투어 클래식으로 할게요");
        assertThat(turn.reply()).contains("몇 분이 가시나요?");
        turn = manager.talk(turn.state(), "11명이요");
        assertThat(turn.reply()).contains("남은 자리는 10명");
        turn = manager.talk(turn.state(), "3명이요");
        assertThat(turn.reply()).contains("3명").contains("클래식 스타일");
        turn = manager.talk(turn.state(), "맞아요");
        turn = manager.talk(turn.state(), "없어요");
        assertThat(turn.done()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM traveler WHERE application_id = ?", Integer.class,
                turn.applicationId())).isEqualTo(3);
    }

    // ---------------------------------------------------------------- 서버 검사 (LLM 이 틀린 값을 내도)

    VoiceBookingManager with(VoiceDialogClient fake) {
        return new VoiceBookingManager(travel, speech, fake, clock);
    }

    static DialogSlots honeymoon(long productId, List<String> drinks) {
        return new DialogSlots("HONEYMOON", "PREMIUM", productId, null, 2, null, null, drinks, null);
    }

    @Test
    void bookIsHeldWhenChoiceChangedInSameTurn() {
        // 직전에 들은 내용과 다른 값으로 바로 BOOK → 다시 확인
        VoiceBookingManager m = with(req -> new DialogReply("예약되었습니다", honeymoon(guam.getProductId(), List.of("WINE")),
                Action.BOOK));
        VoiceDialogState state = new VoiceDialogState(List.of(), honeymoon(guam.getProductId(), List.of()), null);
        VoiceDialogTurn turn = m.talk(state, "와인 넣어서 예약해 줘");
        assertThat(turn.done()).isFalse();
        assertThat(turn.reply()).contains("와인 1병").endsWith("이대로 예약할까요?");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_application", Integer.class)).isZero();
    }

    @Test
    void unknownProductAndInvalidOptionAreRejected() {
        VoiceBookingManager m = with(req -> new DialogReply("좋아요",
                new DialogSlots("HONEYMOON", "PREMIUM", 999_999L, null, 2, null, null, List.of(), null), Action.BOOK));
        VoiceDialogTurn turn = m.talk(new VoiceDialogState(List.of(), DialogSlots.EMPTY, null), "아무거나");
        assertThat(turn.reply()).contains("지금 신청할 수 없어요");
        assertThat(turn.state().slots().productId()).isNull();

        // 허니문에 3성 호텔은 안 됨 → 직전 값 유지
        VoiceBookingManager m2 = with(req -> new DialogReply("3성으로 바꿨어요",
                new DialogSlots("HONEYMOON", "PREMIUM", guam.getProductId(), null, 2, "STAR3", null, List.of(), null),
                Action.ASK));
        turn = m2.talk(new VoiceDialogState(List.of(), honeymoon(guam.getProductId(), List.of()), null), "3성 호텔로");
        assertThat(turn.reply()).startsWith("죄송해요").contains("4등급 이상");
        assertThat(turn.state().slots().hotelLevel()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM travel_application", Integer.class)).isZero();
    }

    @Test
    void isoDatesInReplyAreSpokenKoreanStyle() {
        assertThat(VoiceBookingManager.spokenDates("허니문(괌, 2026-12-24 출발)")).isEqualTo("허니문(괌, 12월 24일 출발)");
    }

    @Test
    void failingClientFallsBackToScriptedTurn() {
        VoiceBookingManager m = with(req -> {
            throw new IllegalStateException("OpenAI HTTP 500");
        });
        VoiceDialogTurn turn = m.talk(m.startVoiceBooking().state(), "멋진 여행 상품 추천해주세요.");
        assertThat(turn.fallback()).isTrue();
        assertThat(turn.reply()).isEqualTo("무슨 기념일인가요?");
    }

    // ---------------------------------------------------------------- OpenAI 요청·응답 형식 (실제 호출 없음)

    @Test
    void openAiRequestCarriesScenarioPromptAndStrictSchema() {
        OpenAIDialogClient c = new OpenAIDialogClient("test-key", "test-model", "https://example.invalid/v1",
                java.time.Duration.ofSeconds(5), (uri, key, body, t) -> {
                    throw new IOException("not used");
                });
        DialogRequest req = new DialogRequest("박단골", today, List.of(new ProductInfo(guam.getProductId(), guam.getProductName(),
                "괌", "선셋 디너 크루즈", "HONEYMOON", "허니문 낭만 투어", "PREMIUM", "프리미엄", guam.getDepartureDate(), 5, 2_000_000, -1)),
                DialogSlots.EMPTY, List.of(), "추천해 주세요");
        Map<String, Object> body = c.requestBody(req);
        String json = JsonMapper.builder().build().writeValueAsString(body);
        assertThat(json).contains("\"strict\":true").contains("voice_booking_turn")
                .contains("다음달 오늘에 결혼해요")          // 시나리오 예시가 시스템 프롬프트에 들어 있다
                .contains("괌 투몬 선셋 허니문").contains("추천해 주세요");
        assertThat(OpenAIDialogClient.schema().get("required")).asList().contains("reply", "product_id", "drinks", "action");
    }

    @Test
    void openAiResponseIsParsedAndHttpErrorsThrow() throws Exception {
        String content = JsonMapper.builder().build().writeValueAsString(Map.of("reply", "무슨 기념일인가요?",
                "theme", "HONEYMOON", "tour_level", "PREMIUM", "product_id", 7, "departure_date", "2026-12-24",
                "traveler_count", 2, "hotel_level", "STAR5", "meal", "FINE",
                "drinks", Map.of("COFFEE", 2, "CHAMPAGNE", 1, "WINE", 0, "BEER", 0),
                "action", "ASK"));
        String ok = JsonMapper.builder().build().writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
        OpenAIDialogClient c = client(200, ok);
        DialogReply r = c.reply(new DialogRequest("박단골", today, List.of(), DialogSlots.EMPTY, List.of(), "추천"));
        assertThat(r.reply()).isEqualTo("무슨 기념일인가요?");
        assertThat(r.action()).isEqualTo(Action.ASK);
        assertThat(r.slots().productId()).isEqualTo(7L);
        assertThat(r.slots().drinks()).containsExactly("CHAMPAGNE", "COFFEE", "COFFEE");

        assertThatThrownBy(() -> client(500, "{\"error\":{}}").reply(
                new DialogRequest("박단골", today, List.of(), DialogSlots.EMPTY, List.of(), "추천")))
                .hasMessageContaining("HTTP 500");
    }

    @SuppressWarnings("unchecked")
    static OpenAIDialogClient client(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return new OpenAIDialogClient("k", "m", "https://example.invalid/v1", java.time.Duration.ofSeconds(5),
                (uri, key, b, t) -> response);
    }

    // ---------------------------------------------------------------- 화면

    @Test
    void voiceBookingPageAndTalkEndpoint() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/speech/booking/").with(login(bride)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("박단골 고객님, 어떤 여행 상품을 예약하시겠습니까?")));
        mvc.perform(post("/speech/booking/talk/").with(login(bride)).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transcript\":\"멋진 여행 상품 추천해주세요.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("무슨 기념일인가요?"))
                .andExpect(jsonPath("$.done").value(false));

        LoginSession staff = principals.create("staffer", Role.STAFF);
        mvc.perform(get("/speech/booking/").with(login(staff))).andExpect(status().isForbidden());
    }
}
