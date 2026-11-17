package com.misterworldtour.travel;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.Role;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.misterworldtour.members.LoginSessionSupport.login;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC7 2a: 직원 상품 관리 목록 조회 실패는 '상품 없음'과 구분해 안내하고 다시 조회하게 한다. */
class StaffProductListFailureTest extends BookingTestBase {

    @MockitoSpyBean
    TravelManager spyTravel;

    @Test
    void listLookupFailureIsShownSeparatelyFromEmpty() throws Exception {
        LoginSession staff = principals.create("emp", Role.STAFF);
        mvc.perform(get("/travel/staff/products/").with(login(staff)))
                .andExpect(content().string(containsString("조건에 맞는 상품이 없습니다")))
                .andExpect(content().string(not(containsString("상품 목록을 불러오지 못했습니다"))));

        doThrow(new DataAccessResourceFailureException("끊김")).when(spyTravel).loadProducts(any(), any());
        mvc.perform(get("/travel/staff/products/").with(login(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상품 목록을 불러오지 못했습니다")))
                .andExpect(content().string(containsString("다시 조회")))
                .andExpect(content().string(not(containsString("조건에 맞는 상품이 없습니다"))));
    }
}
