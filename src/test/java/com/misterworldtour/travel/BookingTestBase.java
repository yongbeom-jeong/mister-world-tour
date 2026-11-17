package com.misterworldtour.travel;

import com.misterworldtour.TestDb;
import com.misterworldtour.dataaccess.DataAccessAPI;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.LoginSessionSupport;
import com.misterworldtour.members.Role;
import com.misterworldtour.souvenir.SouvenirManager;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 신청 테스트 공통. 기념품 재고는 모두 100개로 시작한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class BookingTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TravelManager booking;
    @Autowired
    SouvenirManager souvenirs;
    @Autowired
    DataAccessAPI dataAccess;
    @Autowired
    LoginSessionSupport principals;

    LoginSession alice;

    @BeforeEach
    void baseSetUp() {
        TestDb.clean(jdbc);
        jdbc.update("UPDATE souvenir SET stock_quantity = 100");
        alice = customer("alice", "앨리스", "010-1000-0001");
    }

    LoginSession customer(String username) {
        return customer(username, username, "010-1111-2222");
    }

    LoginSession customer(String username, String name, String contact) {
        return principals.create(username, Role.CUSTOMER, name, contact);
    }

    TravelProduct product(Theme theme, TourLevel level) {
        return product(theme, level, 1_000_000, 30, theme + " 상품");
    }

    TravelProduct product(Theme theme, TourLevel level, int price, int daysAhead, String name) {
        return dataAccess.saveProduct(new TravelProduct(name, "어딘가", "", theme, level, price, 4,
                LocalDate.now().plusDays(daysAhead)));
    }

    /** 첫 행은 신청 고객 본인(계정 정보로 바뀜), 나머지는 동반자. */
    static List<TravelerInput> rows(String... companions) {
        List<TravelerInput> rows = new ArrayList<>();
        rows.add(new TravelerInput("", "", true));
        for (String c : companions) {
            rows.add(new TravelerInput(c, "", false));
        }
        return rows;
    }

    ApplicationReviewResult review(LoginSession member, TravelProduct p, String... companions) {
        return booking.reviewApplication(member.getMemberId(), p.getProductId(), TravelConfiguration.defaultOf(p),
                rows(companions));
    }

    ApplicationResult book(LoginSession member, TravelProduct p, String... companions) {
        return booking.submitTravelApplication(member.getMemberId(), review(member, p, companions));
    }

    int stock(String souvenirName) {
        return jdbc.queryForObject("SELECT stock_quantity FROM souvenir WHERE name = ?", Integer.class, souvenirName);
    }

    long applicationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM travel_application", Long.class);
    }
}
