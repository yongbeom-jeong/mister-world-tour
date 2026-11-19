package com.misterworldtour.speech;

import com.misterworldtour.TestDb;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.LoginSessionSupport;
import com.misterworldtour.members.Role;
import com.misterworldtour.speech.SpeechRecognitionAPI.Kind;
import com.misterworldtour.speech.SpeechRecognitionAPI.ProductCandidate;
import com.misterworldtour.speech.SpeechRecognitionAPI.Status;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionContext;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionResult;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 음성·문장 선택. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpeechTest {

    @Autowired
    SpeechRecognitionManager speech;
    @Autowired
    VoiceSelectionInterpreter interpreter;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LoginSessionSupport principals;

    final VoiceSelectionContext ctx = new VoiceSelectionContext(null, null, List.of(
            new ProductCandidate(1, "몰디브 워터빌라 허니문", "몰디브 말레"),
            new ProductCandidate(2, "다낭 골프 챌린지", "베트남 다낭"),
            new ProductCandidate(3, "제주 힐링 효도 여행", "제주도"),
            new ProductCandidate(4, "한라산·올레길 트레킹", "제주도")));

    VoiceSelectionResult say(String text) {
        return interpreter.interpretSelection(text, ctx);
    }

    @Test
    void themeAndLevelFilter() {
        VoiceSelectionResult r = interpreter.interpretSelection("골프 그랜드 보여줘", new VoiceSelectionContext(null, null, List.of()));
        assertThat(r.kind()).isEqualTo(Kind.FILTER);
        assertThat(r.theme()).isEqualTo(Theme.GOLF);
        assertThat(r.level()).isEqualTo(TourLevel.GRAND);
        VoiceSelectionResult honeymoon = interpreter.interpretSelection("신혼여행", new VoiceSelectionContext(null, null, List.of()));
        assertThat(honeymoon.theme()).isEqualTo(Theme.HONEYMOON);
        assertThat(honeymoon.level()).isNull();
    }

    @Test
    void productByUniqueWordAndOrder() {
        assertThat(say("몰디브").productId()).isEqualTo(1L);
        assertThat(say("두 번째 상품").productId()).isEqualTo(2L);
        assertThat(say("3번").productId()).isEqualTo(3L);
        assertThat(say("열 번째").status()).isEqualTo(Status.UNSUPPORTED);
    }

    @Test
    void ambiguousAndConflictingAndUnsupported() {
        assertThat(say("제주도").status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(interpreter.interpretSelection("골프 트레킹", new VoiceSelectionContext(null, null, List.of())).status())
                .isEqualTo(Status.AMBIGUOUS);
        assertThat(say("안녕하세요").status()).isEqualTo(Status.UNSUPPORTED);
    }

    @Test
    void recognitionFailures() {
        assertThat(speech.recognizeSelection(SpeechServiceClient.VoiceInput.ofTranscript("  "), ctx).status())
                .isEqualTo(Status.RECOGNITION_FAILED);
        SpeechRecognitionManager broken = new SpeechRecognitionManager(input -> {
            throw new IllegalStateException("업체 장애");
        }, interpreter);
        assertThat(broken.recognizeSelection(SpeechServiceClient.VoiceInput.ofTranscript("골프"), ctx).message())
                .contains("연결하지 못했습니다");
        assertThat(speech.recognizeSelection(SpeechServiceClient.VoiceInput.ofTranscript("몰디브"), ctx).recognizedText())
                .isEqualTo("몰디브");
        assertThat(speech.recordBrowserFailure("<script>", 1L)).isEqualTo("other");
    }

    @Test
    void recognizeEndpointChoosesFromCurrentListOnly() throws Exception {
        TestDb.clean(jdbc);
        LoginSession customer = principals.create("cust", Role.CUSTOMER);
        jdbc.update("INSERT INTO travel_product (name, destination, description, theme, tour_level, base_price, travel_days, "
                + "departure_date, theme_service, hotel_level, transport, meal, departure_status, created_at) VALUES "
                + "('다낭 골프', '베트남 다낭', '', 'GOLF', 'GRAND', 1000, 3, DATEADD('DAY', 10, CURRENT_DATE), 's', 'STAR4', "
                + "'LUXURY_VAN_10', 'LOCAL', 'PENDING', CURRENT_TIMESTAMP)");
        long id = jdbc.queryForObject("SELECT id FROM travel_product", Long.class);
        mvc.perform(post("/speech/recognize/").with(login(customer)).with(csrf()).contentType("application/json")
                        .content("{\"transcript\":\"다낭\",\"theme\":\"GOLF\"}"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.kind").value("PRODUCT"))
                .andExpect(jsonPath("$.product_id").value(id));
        // 다른 테마 목록에서는 그 상품이 없으므로 고르지 않는다
        mvc.perform(post("/speech/recognize/").with(login(customer)).with(csrf()).contentType("application/json")
                        .content("{\"transcript\":\"다낭\",\"theme\":\"TREKKING\"}"))
                .andExpect(jsonPath("$.status").value("UNSUPPORTED"));
        mvc.perform(post("/speech/failure/").with(login(customer)).with(csrf()).contentType("application/json")
                .content("{\"error\":\"no-speech\"}")).andExpect(status().isNoContent());
        LoginSession staff = principals.create("emp", Role.STAFF);
        mvc.perform(post("/speech/recognize/").with(login(staff)).with(csrf()).contentType("application/json")
                .content("{}")).andExpect(status().isForbidden());
    }
}
