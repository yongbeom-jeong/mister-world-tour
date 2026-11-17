package com.misterworldtour.souvenir;

import com.misterworldtour.TestDb;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.LoginSessionSupport;
import com.misterworldtour.members.Role;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptException;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptStatus;
import com.misterworldtour.travel.Theme;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기념품 입고(UC8)·품목 등록·수정·삭제·차감 내역 (souvenir/tests.py). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SouvenirWebTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LoginSessionSupport principals;
    @Autowired
    SouvenirManager souvenirs;
    @Autowired
    SouvenirManager receipts;

    final JsonMapper json = JsonMapper.builder().build();
    LoginSession staff;
    LoginSession customer;
    long golfBall;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        staff = principals.create("emp", Role.STAFF);
        customer = principals.create("cust", Role.CUSTOMER);
        golfBall = jdbc.queryForObject("SELECT id FROM souvenir WHERE name = '골프공'", Long.class);
    }

    int stock(long id) {
        return jdbc.queryForObject("SELECT stock_quantity FROM souvenir WHERE id = ?", Integer.class, id);
    }

    int receiptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_receipt", Integer.class);
    }

    @Test
    void fourThemeSouvenirsRegistered() {
        assertThat(souvenirs.loadSouvenirs()).extracting(Souvenir::getSouvenirName)
                .containsExactlyInAnyOrder("커플 기념티", "건강 인삼", "골프공", "기념품 스카프");
    }

    // ---------------------------------------------------------------- 입고 서비스

    @Test
    void prepareDoesNotSaveAndRecordIncreasesOnce() {
        var p = receipts.prepareStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5");
        assertThat(receiptCount()).isZero();
        var first = receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5", p.requestKey());
        assertThat(first.status()).isEqualTo(StockReceiptStatus.SUCCESS);
        assertThat(first.receipt().getStockAfter()).isEqualTo(5);
        // 응답을 받지 못해 같은 키로 다시 확인: 재고는 다시 늘지 않는다
        var again = receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5", p.requestKey());
        assertThat(again.status()).isEqualTo(StockReceiptStatus.SUCCESS);
        assertThat(again.alreadyRecorded()).isTrue();
        assertThat(stock(golfBall)).isEqualTo(5);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentContentIsConflict() {
        var p = receipts.prepareStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5");
        receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5", p.requestKey());
        assertThat(receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "6", p.requestKey()).status())
                .isEqualTo(StockReceiptStatus.CONFLICT);
        assertThat(stock(golfBall)).isEqualTo(5);
    }

    @Test
    void unpreparedKeyRejected() {
        assertThat(receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "5", "nokey")
                .status()).isEqualTo(StockReceiptStatus.CONFLICT);
        assertThat(stock(golfBall)).isZero();
    }

    @Test
    void invalidQuantityAndSouvenir() {
        for (String q : new String[]{"", "0", "-1", "1.5", "abc", "10001", "99999999999999999999"}) {
            assertThatThrownBy(() -> receipts.prepareStockReceipt(staff.getMemberId(), String.valueOf(golfBall), q))
                    .isInstanceOfSatisfying(StockReceiptException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(StockReceiptStatus.INVALID_INPUT));
        }
        assertThatThrownBy(() -> receipts.prepareStockReceipt(staff.getMemberId(), "999999", "1"))
                .isInstanceOfSatisfying(StockReceiptException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(StockReceiptStatus.NOT_FOUND));
    }

    @Test
    void separateReceiptsAccumulate() {
        for (int i = 0; i < 2; i++) {
            var p = receipts.prepareStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "7");
            receipts.recordSouvenirStockReceipt(staff.getMemberId(), String.valueOf(golfBall), "7", p.requestKey());
        }
        assertThat(stock(golfBall)).isEqualTo(14);
        assertThat(receiptCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 입고 HTTP

    @Test
    void fullFlowThroughHttp() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/souvenir/staff/stock/list/").session(session).with(login(staff)))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.souvenirs.length()").value(4));
        MvcResult prepared = mvc.perform(post("/souvenir/staff/stock/prepare/").session(session).with(login(staff))
                        .with(csrf()).param("souvenir_id", String.valueOf(golfBall)).param("quantity", "12"))
                .andExpect(status().isOk()).andReturn();
        JsonNode p = json.readTree(prepared.getResponse().getContentAsString());
        mvc.perform(post("/souvenir/staff/stock/record/").session(session).with(login(staff)).with(csrf())
                        .param("souvenir_id", String.valueOf(golfBall)).param("quantity", "12")
                        .param("request_key", p.get("request_key").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.current_souvenir.stock_quantity").value(12))
                .andExpect(jsonPath("$.receipt.stock_after").value(12));
        mvc.perform(post("/souvenir/staff/stock/prepare/").session(session).with(login(staff)).with(csrf())
                        .param("souvenir_id", String.valueOf(golfBall)).param("quantity", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("INVALID_INPUT"));
    }

    @Test
    void anonymousAndCustomerGetPermissionDeniedJson() throws Exception {
        mvc.perform(get("/souvenir/staff/stock/list/"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value("PERMISSION_DENIED"));
        mvc.perform(post("/souvenir/staff/stock/record/").with(login(customer)).with(csrf())
                        .param("souvenir_id", "1").param("quantity", "1").param("request_key", "k"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value("PERMISSION_DENIED"));
        mvc.perform(post("/souvenir/staff/stock/prepare/").with(login(staff))
                        .param("souvenir_id", "1").param("quantity", "1")) // CSRF 토큰 없음 (로그인 만료 후 등)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value("PERMISSION_DENIED"));
        assertThat(receiptCount()).isZero();
    }

    @Test
    void pagesAreStaffOnly() throws Exception {
        for (String url : new String[]{"/souvenir/staff/stock/", "/souvenir/staff/souvenirs/",
                "/souvenir/staff/souvenirs/new/", "/souvenir/staff/souvenirs/issues/"}) {
            mvc.perform(get(url).with(login(customer))).andExpect(status().isForbidden());
            mvc.perform(get(url).with(login(staff))).andExpect(status().isOk());
        }
        mvc.perform(get("/souvenir/staff/souvenirs/")).andExpect(redirectedUrl("/members/login/"));
    }

    // ---------------------------------------------------------------- 품목 등록·수정·삭제

    @Test
    void registerLinksThemeAndOpensStockPage() throws Exception {
        MvcResult res = mvc.perform(post("/souvenir/staff/souvenirs/new/").with(login(staff)).with(csrf())
                .param("name", " 골프 장갑 ").param("theme", "GOLF")).andReturn();
        Souvenir s = souvenirs.souvenirsForTheme(Theme.GOLF).stream()
                .filter(x -> x.getSouvenirName().equals("골프 장갑")).findFirst().orElseThrow();
        assertThat(res.getResponse().getRedirectedUrl()).isEqualTo("/souvenir/staff/stock/?selected=" + s.getSouvenirId());
        assertThat(s.getStockQuantity()).isZero();
    }

    @Test
    void registerValidation() throws Exception {
        mvc.perform(post("/souvenir/staff/souvenirs/new/").with(login(staff)).with(csrf())
                        .param("name", "").param("theme", ""))
                .andExpect(content().string(containsString("품목 이름을 입력해 주세요")))
                .andExpect(content().string(containsString("연결할 테마를 선택해 주세요")));
        mvc.perform(post("/souvenir/staff/souvenirs/new/").with(login(staff)).with(csrf())
                        .param("name", "골프공").param("theme", "GOLF"))
                .andExpect(content().string(containsString("이미 등록된 품목 이름입니다")));
        assertThatThrownBy(() -> souvenirs.registerSouvenir("x".repeat(51), Theme.GOLF))
                .isInstanceOf(SouvenirManager.SouvenirRegistrationException.class);
    }

    @Test
    void caseInsensitiveName() {
        souvenirs.registerSouvenir("Golf Glove", Theme.GOLF);
        assertThatThrownBy(() -> souvenirs.registerSouvenir("golf GLOVE", Theme.GOLF))
                .isInstanceOf(SouvenirManager.SouvenirRegistrationException.class);
    }

    @Test
    void editNameAndThemeAndWarnWhenThemeEmpties() throws Exception {
        long scarf = jdbc.queryForObject("SELECT id FROM souvenir WHERE name = '기념품 스카프'", Long.class);
        MvcResult res = mvc.perform(post("/souvenir/staff/souvenirs/{id}/edit/", scarf).with(login(staff)).with(csrf())
                .param("name", "등산 스카프").param("theme", "GOLF")).andReturn();
        assertThat(res.getResponse().getRedirectedUrl()).isEqualTo("/souvenir/staff/souvenirs/");
        assertThat(res.getFlashMap().get("messages").toString()).contains("아웃도어 트레킹 투어에 연결된 기념품이 없어");
        Souvenir s = souvenirs.getSouvenir(scarf);
        assertThat(s.getSouvenirName()).isEqualTo("등산 스카프");
        assertThat(s.getTheme()).isEqualTo(Theme.GOLF);
        mvc.perform(post("/souvenir/staff/souvenirs/{id}/edit/", scarf).with(login(staff)).with(csrf())
                        .param("name", "골프공").param("theme", "GOLF"))
                .andExpect(content().string(containsString("이미 등록된 품목 이름입니다")));
    }

    @Test
    void deleteWithoutHistoryRemovesRow() throws Exception {
        Souvenir s = souvenirs.registerSouvenir("임시 품목", Theme.GOLF);
        mvc.perform(post("/souvenir/staff/souvenirs/{id}/delete/", s.getSouvenirId()).with(login(staff)).with(csrf()))
                .andExpect(redirectedUrl("/souvenir/staff/souvenirs/"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM souvenir WHERE id = ?", Integer.class, s.getSouvenirId())).isZero();
    }

    @Test
    void deleteWithHistoryHidesAndKeepsRecords() throws Exception {
        souvenirs.seedStock("골프공", 3, staff.getMemberId(), "k1");
        mvc.perform(post("/souvenir/staff/souvenirs/{id}/delete/", golfBall).with(login(staff)).with(csrf()))
                .andExpect(redirectedUrl("/souvenir/staff/souvenirs/"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM souvenir WHERE id = ?", Boolean.class, golfBall)).isFalse();
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(souvenirs.souvenirsForTheme(Theme.GOLF)).isEmpty();
        // 숨긴 품목과 같은 이름은 다시 쓸 수 없음
        assertThatThrownBy(() -> souvenirs.registerSouvenir("골프공", Theme.GOLF))
                .hasMessageContaining("삭제한 품목 중 같은 이름");
        mvc.perform(get("/souvenir/staff/souvenirs/{id}/edit/", golfBall).with(login(staff)))
                .andExpect(redirectedUrl("/souvenir/staff/souvenirs/"));
    }

    @Test
    void issueListShowsDeductions() throws Exception {
        jdbc.update("UPDATE souvenir SET stock_quantity = 100");
        jdbc.update("INSERT INTO travel_product (name, destination, description, theme, tour_level, base_price, "
                + "travel_days, departure_date, theme_service, hotel_level, transport, meal, departure_status, created_at) "
                + "VALUES ('다낭 골프', 'd', '', 'GOLF', 'GRAND', 1000, 3, DATEADD('DAY', 10, CURRENT_DATE), 's', "
                + "'STAR4', 'LUXURY_VAN_10', 'LOCAL', 'CONFIRMED', CURRENT_TIMESTAMP)");
        long productId = jdbc.queryForObject("SELECT id FROM travel_product", Long.class);
        jdbc.update("INSERT INTO travel_application (member_id, product_id, hotel_level, transport, meal, unit_price, "
                + "subtotal, final_amount, request_key, submitted_at) VALUES (?, ?, 'STAR4', 'LUXURY_VAN_10', 'LOCAL', "
                + "1, 1, 1, 'k', CURRENT_TIMESTAMP)", customer.getMemberId(), productId);
        long appId = jdbc.queryForObject("SELECT id FROM travel_application", Long.class);
        souvenirs.issueForDeparture(Theme.GOLF, java.util.List.of(new SouvenirManager.IssueItem(appId, 3)));
        mvc.perform(get("/souvenir/staff/souvenirs/issues/").param("souvenir", String.valueOf(golfBall)).with(login(staff)))
                .andExpect(content().string(containsString("다낭 골프")))
                .andExpect(content().string(containsString("#" + appId + " 이름cust")))
                .andExpect(content().string(containsString("−3개")))
                .andExpect(content().string(containsString("97개")));
        mvc.perform(get("/souvenir/staff/souvenirs/issues/").param("page", "abc").with(login(staff)))
                .andExpect(status().isOk());
    }

    @Test
    void staffHomeShowsLowStock() throws Exception {
        mvc.perform(get("/").with(login(staff)))
                .andExpect(content().string(containsString("재고 부족 기념품")))
                .andExpect(content().string(containsString("골프공")));
    }
}
