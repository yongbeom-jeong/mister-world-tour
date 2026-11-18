package com.misterworldtour.ui;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.StaffRoleRequired;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 역할 검사 어노테이션이 동작하는지 (비로그인 → 로그인 화면, 다른 역할 → 403). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoleAccessTest.Endpoints.class)
class RoleAccessTest {

    @TestConfiguration
    static class Endpoints {
        @RestController
        static class Probe {
            @GetMapping("/test/customer-only")
            @CustomerRequired
            String customerOnly() {
                return "customer ok";
            }

            @GetMapping("/test/staff-only")
            @StaffRoleRequired
            String staffOnly() {
                return "staff ok";
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousGoesToLogin() throws Exception {
        mvc.perform(get("/test/customer-only")).andExpect(redirectedUrl("/members/login/"));
    }

    @Test
    void rightRolePasses() throws Exception {
        mvc.perform(get("/test/customer-only").with(user("c").roles("CUSTOMER")))
                .andExpect(content().string("customer ok"));
        mvc.perform(get("/test/staff-only").with(user("s").roles("STAFF")))
                .andExpect(content().string("staff ok"));
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        mvc.perform(get("/test/staff-only").with(user("c").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicPagesRender() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("테마로 둘러보기")));
        mvc.perform(get("/credits/")).andExpect(status().isOk()).andExpect(content().string(containsString("저작자")));
        mvc.perform(get("/members/signup/")).andExpect(status().isOk());
        mvc.perform(get("/css/mwt.css")).andExpect(status().isOk());
    }
}
