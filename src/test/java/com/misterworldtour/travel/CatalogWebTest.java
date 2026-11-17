package com.misterworldtour.travel;

import com.misterworldtour.TestDb;
import com.misterworldtour.members.MemberPrincipalSupport;
import com.misterworldtour.members.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 고객 상품 목록·상세와 홈 (UC4 조회). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogWebTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TravelProductRepository products;
    @Autowired
    MemberPrincipalSupport principals;

    UserDetails customer;
    UserDetails staff;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        customer = principals.create("cust", Role.CUSTOMER);
        staff = principals.create("emp", Role.STAFF);
    }

    TravelProduct save(String name, Theme theme, TourLevel level, LocalDate departure) {
        return products.save(new TravelProduct(name, "여행지", "", theme, level, 1_000_000, 5, departure));
    }

    @Test
    void listShowsOnlyFutureProductsInDepartureOrder() throws Exception {
        LocalDate today = LocalDate.now();
        save("나중", Theme.GOLF, TourLevel.GRAND, today.plusDays(20));
        save("먼저", Theme.GOLF, TourLevel.GRAND, today.plusDays(10));
        save("오늘 출발", Theme.GOLF, TourLevel.GRAND, today);
        save("지난 상품", Theme.GOLF, TourLevel.GRAND, today.minusDays(1));
        @SuppressWarnings("unchecked")
        List<TravelProduct> shown = (List<TravelProduct>) mvc.perform(get("/travel/products/").with(user(customer)))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("products");
        assertThat(shown).extracting(TravelProduct::getName).containsExactly("먼저", "나중");
    }

    @Test
    void filterByThemeAndLevel() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        save("골프 그랜드", Theme.GOLF, TourLevel.GRAND, d);
        save("골프 클래식", Theme.GOLF, TourLevel.CLASSIC, d);
        save("트레킹 그랜드", Theme.TREKKING, TourLevel.GRAND, d);
        mvc.perform(get("/travel/products/").param("theme", "GOLF").param("tour_level", "GRAND").with(user(customer)))
                .andExpect(content().string(containsString("골프 그랜드")))
                .andExpect(content().string(not(containsString("골프 클래식"))))
                .andExpect(content().string(not(containsString("트레킹 그랜드"))));
    }

    @Test
    void restrictedThemeWithClassicShowsNotice() throws Exception {
        mvc.perform(get("/travel/products/").param("theme", "HONEYMOON").param("tour_level", "CLASSIC").with(user(customer)))
                .andExpect(model().attribute("notice", containsString("그랜드 또는 프리미엄")));
    }

    @Test
    void emptyListMessage() throws Exception {
        mvc.perform(get("/travel/products/").with(user(customer)))
                .andExpect(content().string(containsString("지금 신청할 수 있는 여행 상품이 없습니다")));
        mvc.perform(get("/travel/products/").param("theme", "GOLF").with(user(customer)))
                .andExpect(content().string(containsString("조건에 맞는 상품이 없습니다")));
    }

    @Test
    void detailShowsProductAndRemainingSeats() throws Exception {
        TravelProduct p = save("다낭 골프", Theme.GOLF, TourLevel.GRAND, LocalDate.now().plusDays(10));
        mvc.perform(get("/travel/products/{id}/", p.getId()).with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("다낭 골프")))
                .andExpect(content().string(containsString("남은 자리 10명")));
    }

    @Test
    void pastOrMissingProductRedirectsToList() throws Exception {
        TravelProduct past = save("지난 상품", Theme.GOLF, TourLevel.GRAND, LocalDate.now());
        mvc.perform(get("/travel/products/{id}/", past.getId()).with(user(customer)))
                .andExpect(redirectedUrl("/travel/products/"));
        mvc.perform(get("/travel/products/{id}/", 999_999).with(user(customer)))
                .andExpect(redirectedUrl("/travel/products/"));
    }

    @Test
    void staffCannotOpenCustomerCatalog() throws Exception {
        mvc.perform(get("/travel/products/").with(user(staff))).andExpect(status().isForbidden());
    }

    @Test
    void homeShowsFeaturedForCustomerAndStatsForStaff() throws Exception {
        save("곧 출발", Theme.GOLF, TourLevel.GRAND, LocalDate.now().plusDays(3));
        mvc.perform(get("/").with(user(customer)))
                .andExpect(content().string(containsString("곧 출발하는 여행")))
                .andExpect(content().string(containsString("곧 출발")));
        mvc.perform(get("/").with(user(staff)))
                .andExpect(content().string(containsString("등록 상품")))
                .andExpect(content().string(containsString("최근 등록한 상품")))
                .andExpect(content().string(containsString("신청 0건")));
    }
}
