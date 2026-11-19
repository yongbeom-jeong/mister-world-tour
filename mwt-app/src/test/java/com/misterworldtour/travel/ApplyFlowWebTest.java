package com.misterworldtour.travel;

import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 상품 상세 → 구성 → 신청 화면 → 검토 → 확정 (HTTP 흐름). */
class ApplyFlowWebTest extends BookingTestBase {

    @MockitoSpyBean
    TravelManager spyBooking;

    final JsonMapper json = JsonMapper.builder().build();
    final MockHttpSession session = new MockHttpSession();

    String configJson(TravelProduct p) {
        return json.writeValueAsString(TravelConfiguration.defaultOf(p).toMap());
    }

    @Test
    void configureEndpoint() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.CLASSIC);
        String url = "/travel/products/" + p.getProductId() + "/configure/";
        String body = json.writeValueAsString(Map.of("current", TravelConfiguration.defaultOf(p).toMap(),
                "changes", Map.of("meal", "LOCAL")));
        mvc.perform(post(url).with(login(alice)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit_price").value(1_080_000))
                .andExpect(jsonPath("$.described.meal").value("현지식 레스토랑 식사"));
        String bad = json.writeValueAsString(Map.of("current", TravelConfiguration.defaultOf(p).toMap(),
                "changes", Map.of("hotel_level", "STAR9")));
        mvc.perform(post(url).with(login(alice)).with(csrf()).contentType("application/json").content(bad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post(url).with(login(alice)).with(csrf()).contentType("application/json").content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailShowsOptionsAndHidesUnavailableOnes() throws Exception {
        TravelProduct p = product(Theme.FILIAL, TourLevel.GRAND);
        mvc.perform(get("/travel/products/{id}/", p.getProductId()).with(login(alice)))
                .andExpect(content().string(containsString("나만의 여행 구성")))
                .andExpect(content().string(containsString("건강 인삼")))
                .andExpect(content().string(containsString("value=\"STAR4\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("value=\"STAR3\""))));
    }

    @Test
    void fullFlow() throws Exception {
        TravelProduct p = product(Theme.HONEYMOON, TourLevel.PREMIUM);
        mvc.perform(post("/travel/products/{id}/apply/", p.getProductId()).session(session).with(login(alice)).with(csrf())
                        .param("config", configJson(p)))
                .andExpect(redirectedUrl("/travel/apply/"));

        MvcResult res = mvc.perform(post("/travel/apply/").session(session).with(login(alice)).with(csrf())
                        .param("action", "review")
                        .param("traveler_name", "무시됨", "배우자").param("traveler_contact", "", "01099990000"))
                .andExpect(status().isOk()).andReturn();
        ApplicationReviewResult review = (ApplicationReviewResult) res.getModelAndView().getModel().get("review");
        assertThat(review.price().travelerCount()).isEqualTo(2);
        assertThat(review.travelers().get(0).name()).isEqualTo("앨리스");
        assertThat(review.travelers().get(1).contact()).isEqualTo("010-9999-0000");

        MvcResult submitted = mvc.perform(post("/travel/apply/").session(session).with(login(alice)).with(csrf())
                .param("action", "submit").param("request_key", review.requestKey())).andReturn();
        long appId = jdbc.queryForObject("SELECT MIN(id) FROM travel_application", Long.class);
        assertThat(submitted.getResponse().getRedirectedUrl()).isEqualTo("/travel/applications/" + appId + "/");
        mvc.perform(get("/travel/applications/{id}/", appId).session(session).with(login(alice))
                        .flashAttrs(submitted.getFlashMap()))
                .andExpect(content().string(containsString("출발 확정")))
                .andExpect(content().string(containsString("허니문 2인 신청으로 출발이 확정되었습니다")))
                .andExpect(content().string(containsString("출발 확정 문자: 성공 1건")));

        // 뒤로 가기 후 같은 키로 다시 제출: 중복 저장 없이 기존 신청으로 안내
        mvc.perform(post("/travel/apply/").session(session).with(login(alice)).with(csrf())
                        .param("action", "submit").param("request_key", review.requestKey()))
                .andExpect(redirectedUrl("/travel/applications/" + appId + "/"));
        assertThat(applicationCount()).isEqualTo(1);

        mvc.perform(get("/travel/applications/").session(session).with(login(alice)))
                .andExpect(content().string(containsString(p.getProductName())))
                .andExpect(content().string(containsString("여행자 2명")));
    }

    String reviewed() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        mvc.perform(post("/travel/products/{id}/apply/", p.getProductId()).session(session).with(login(alice)).with(csrf())
                .param("config", configJson(p)));
        MvcResult res = mvc.perform(post("/travel/apply/").session(session).with(login(alice)).with(csrf())
                .param("action", "review").param("traveler_name", "무시됨").param("traveler_contact", "")).andReturn();
        return ((ApplicationReviewResult) res.getModelAndView().getModel().get("review")).requestKey();
    }

    MvcResult submit(String key) throws Exception {
        return mvc.perform(post("/travel/apply/").session(session).with(login(alice)).with(csrf())
                .param("action", "submit").param("request_key", key)).andReturn();
    }

    @Test
    void dbErrorBeforeSaveKeepsReviewForRetry() throws Exception {
        String key = reviewed();
        doThrow(new DataAccessResourceFailureException("끊김")).when(spyBooking).submitTravelApplication(anyLong(), any(), any(), any(), any());
        MvcResult res = submit(key);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getContentAsString()).contains("신청이 저장되지 않았습니다");
        assertThat(((ApplicationReviewResult) res.getModelAndView().getModel().get("review")).requestKey()).isEqualTo(key);
        assertThat(applicationCount()).isZero();

        org.mockito.Mockito.reset(spyBooking);
        submit(key);
        assertThat(applicationCount()).isEqualTo(1);
    }

    @Test
    void dbErrorAndLookupFailureSaysUnknown() throws Exception {
        String key = reviewed();
        doThrow(new DataAccessResourceFailureException("끊김")).when(spyBooking).submitTravelApplication(anyLong(), any(), any(), any(), any());
        doThrow(new DataAccessResourceFailureException("끊김")).when(spyBooking).findSubmittedApplication(anyLong(), any());
        assertThat(submit(key).getResponse().getContentAsString()).contains("반영 여부를 확인하지 못했습니다");
    }

    @Test
    void applyWithoutSelectionRedirects() throws Exception {
        mvc.perform(get("/travel/apply/").with(login(alice))).andExpect(redirectedUrl("/travel/products/"));
    }

    @Test
    void cannotViewOthersApplication() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        long appId = book(customer("bob"), p).applicationId();
        mvc.perform(get("/travel/applications/{id}/", appId).with(login(alice))).andExpect(status().isNotFound());
    }
}
