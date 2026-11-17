package com.misterworldtour.travel;

import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.members.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 직원 상품 등록(UC6)·관리(UC7) (Django 판 test_staff_products.py). */
@TestPropertySource(properties = "mwt.media-root=target/test-media")
class StaffProductWebTest extends BookingTestBase {

    MemberPrincipal staff;

    @BeforeEach
    void staff() {
        staff = principals.create("emp", Role.STAFF);
    }

    MockMultipartHttpServletRequestBuilder form(String url, String... overrides) {
        MockMultipartHttpServletRequestBuilder b = multipart(url);
        String[] base = {"name", "다낭 골프", "destination", "베트남 다낭", "description", "소개", "theme", "GOLF",
                "tourLevel", "GRAND", "basePrice", "1490000", "travelDays", "5",
                "departureDate", LocalDate.now().plusDays(30).toString(), "hotelLevel", "", "meal", "", "transport", ""};
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < base.length; i += 2) {
            values.put(base[i], base[i + 1]);
        }
        for (int i = 0; i < overrides.length; i += 2) {
            values.put(overrides[i], overrides[i + 1]);
        }
        values.forEach(b::param);
        b.with(user(staff)).with(csrf());
        return b;
    }

    TravelProduct only() {
        return products.findAll().get(0);
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void registerSetsDefaultConfiguration() throws Exception {
        MvcResult res = mvc.perform(form("/travel/staff/products/new/")).andReturn();
        TravelProduct p = only();
        assertThat(res.getResponse().getRedirectedUrl()).isEqualTo("/travel/staff/products/" + p.getId() + "/");
        assertThat(p.getHotelLevel()).isEqualTo(HotelLevel.STAR4);
        assertThat(p.getMeal()).isEqualTo(MealOption.LOCAL);
        assertThat(p.getTransport()).isEqualTo(TransportOption.LUXURY_VAN_10);
        assertThat(p.getDepartureStatus()).isEqualTo(DepartureStatus.PENDING);
        assertThat(p.getThemeService()).isEqualTo("골프 리조트 테마 연출");
    }

    @Test
    void registerValidation() throws Exception {
        mvc.perform(form("/travel/staff/products/new/", "name", "", "basePrice", "5", "travelDays", "31",
                        "departureDate", LocalDate.now().toString(), "theme", "HONEYMOON", "tourLevel", "CLASSIC",
                        "destination", ""))
                .andExpect(content().string(containsString("1인 기본 가격은 10,000원 이상")))
                .andExpect(content().string(containsString("여행 기간은 1~30일")))
                .andExpect(content().string(containsString("출발일은 내일 이후")))
                .andExpect(content().string(containsString("그랜드 또는 프리미엄만")));
        assertThat(products.count()).isZero();
    }

    @Test
    void staffCanUpgradeBaseConfigurationButNotBelowDefaults() throws Exception {
        mvc.perform(form("/travel/staff/products/new/", "hotelLevel", "STAR3", "transport", "LUXURY_CAR_2"))
                .andExpect(content().string(containsString("4등급 호텔 이상이어야")))
                .andExpect(content().string(containsString("선택할 수 없는 교통")));
        assertThat(products.count()).isZero();
        mvc.perform(form("/travel/staff/products/new/", "hotelLevel", "STAR5", "meal", "FINE", "transport", "LIMOUSINE"));
        TravelProduct p = only();
        assertThat(p.getHotelLevel()).isEqualTo(HotelLevel.STAR5);
        assertThat(p.getTransport()).isEqualTo(TransportOption.LIMOUSINE);
    }

    @Test
    void customerCannotUseStaffPages() throws Exception {
        mvc.perform(get("/travel/staff/products/").with(user(alice))).andExpect(status().isForbidden());
        mvc.perform(get("/travel/staff/products/new/").with(user(alice))).andExpect(status().isForbidden());
    }

    @Test
    void defaultConfigurationEndpointListsChoices() throws Exception {
        mvc.perform(get("/travel/staff/default-configuration/").param("theme", "GOLF").param("tour_level", "GRAND")
                        .with(user(staff)))
                .andExpect(jsonPath("$.configuration.themeService").value("골프 리조트 테마 연출"))
                .andExpect(jsonPath("$.configuration.souvenir").value("골프공"))
                .andExpect(jsonPath("$.configuration.choices.hotel_level[0][0]").value("STAR4"))
                .andExpect(jsonPath("$.configuration.choices.transport[0][0]").value("LUXURY_VAN_10"));
        mvc.perform(get("/travel/staff/default-configuration/").param("theme", "X").with(user(staff)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selectingDoesNotChangeAnythingAndFieldIdsAreUnique() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        String html = mvc.perform(get("/travel/staff/products/{id}/", p.getId()).with(user(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("filter_theme").contains("id=\"id_theme\"");
        assertThat(html.split("id=\"id_theme\"", -1)).hasSize(2); // 한 번만
        assertThat(products.findById(p.getId()).orElseThrow().getName()).isEqualTo(p.getName());
    }

    @Test
    void registerWithPhotoAndRemoveIt() throws Exception {
        mvc.perform(form("/travel/staff/products/new/").file(new MockMultipartFile("image", "big.png", "image/png",
                png(3200, 1600))));
        TravelProduct p = only();
        assertThat(p.getImage()).startsWith("products/").endsWith(".jpg");
        BufferedImage saved = ImageIO.read(Path.of("target/test-media").resolve(p.getImage()).toFile());
        assertThat(saved.getWidth()).isEqualTo(1600); // 최대 1600px 로 줄임
        assertThat(saved.getHeight()).isEqualTo(800);
        assertThat(p.getPhotoUrl()).isEqualTo("/media/" + p.getImage());

        mvc.perform(form("/travel/staff/products/" + p.getId() + "/").param("image-clear", "on"));
        assertThat(products.findById(p.getId()).orElseThrow().getImage()).isNull();
        Files.deleteIfExists(Path.of("target/test-media").resolve(p.getImage()));
    }

    @Test
    void nonImageUploadRejected() throws Exception {
        mvc.perform(form("/travel/staff/products/new/").file(new MockMultipartFile("image", "a.txt", "text/plain",
                        "hello".getBytes())))
                .andExpect(content().string(containsString("사진 파일을 읽을 수 없습니다")));
        assertThat(products.count()).isZero();
    }

    @Test
    void modifyRegeneratesDefaultConfigurationAndValidationKeepsSaved() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        String url = "/travel/staff/products/" + p.getId() + "/";
        mvc.perform(form(url, "tourLevel", "PREMIUM", "name", "새 이름")).andExpect(redirectedUrl(url));
        TravelProduct after = products.findById(p.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("새 이름");
        assertThat(after.getHotelLevel()).isEqualTo(HotelLevel.STAR5);
        mvc.perform(form(url, "basePrice", "1")).andExpect(content().string(containsString("1인 기본 가격은")));
        assertThat(products.findById(p.getId()).orElseThrow().getBasePrice()).isEqualTo(1_490_000);
    }

    @Test
    void modifyAndDeleteBlockedWhenApplied() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        book(alice, p);
        String url = "/travel/staff/products/" + p.getId() + "/";
        mvc.perform(get(url).with(user(staff))).andExpect(content().string(containsString("수정·삭제할 수 없습니다")));
        mvc.perform(form(url, "name", "바꿈")).andExpect(content().string(containsString("신청이 있는 상품은 수정할 수 없습니다")));
        mvc.perform(post(url + "delete/").with(user(staff)).with(csrf())).andExpect(redirectedUrl(url));
        assertThat(products.findById(p.getId()).orElseThrow().getName()).isEqualTo(p.getName());
    }

    @Test
    void deleteAndMissingProductRedirects() throws Exception {
        TravelProduct p = product(Theme.GOLF, TourLevel.GRAND);
        mvc.perform(post("/travel/staff/products/{id}/delete/", p.getId()).with(user(staff)).with(csrf()))
                .andExpect(redirectedUrl("/travel/staff/products/"));
        assertThat(products.count()).isZero();
        mvc.perform(get("/travel/staff/products/{id}/", p.getId()).with(user(staff)))
                .andExpect(redirectedUrl("/travel/staff/products/"));
    }

    @Test
    void listFilter() throws Exception {
        product(Theme.GOLF, TourLevel.GRAND, 1_000_000, 30, "골프 상품");
        product(Theme.TREKKING, TourLevel.CLASSIC, 1_000_000, 30, "트레킹 상품");
        MockHttpServletRequestBuilder req = get("/travel/staff/products/").param("theme", "GOLF").with(user(staff));
        mvc.perform(req)
                .andExpect(content().string(containsString("골프 상품")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("트레킹 상품"))));
    }
}
