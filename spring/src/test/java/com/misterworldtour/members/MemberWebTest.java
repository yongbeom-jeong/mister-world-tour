package com.misterworldtour.members;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC1 회원가입·UC2 로그인 (Django 판 apps/members/tests.py 를 옮김). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberWebTest {

    static final String PW = "Travel-2026!x";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    MemberService memberService;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM member");
    }

    static MultiValueMap<String, String> signupData(String... overrides) {
        MultiValueMap<String, String> d = new LinkedMultiValueMap<>();
        d.add("memberType", "CUSTOMER");
        d.add("username", "hong_1");
        d.add("password1", PW);
        d.add("password2", PW);
        d.add("name", "홍길동");
        d.add("address", "서울시 종로구");
        d.add("contact", "01012345678");
        d.add("staffCode", "");
        for (int i = 0; i < overrides.length; i += 2) {
            d.set(overrides[i], overrides[i + 1]);
        }
        return d;
    }

    MockHttpServletRequestBuilder signup(String... overrides) {
        return post("/members/signup/").params(signupData(overrides)).with(csrf());
    }

    Member createMember(String username, Role role) {
        return members.saveAndFlush(new Member(username, passwordEncoder.encode(PW), "이름", "", "010-0000-0000", role));
    }

    // ---------------------------------------------------------------- UC1 회원가입

    @Test
    void customerSignupSavesHashedPasswordAndNormalizedContact() throws Exception {
        mvc.perform(signup())
                .andExpect(redirectedUrl("/members/login/"))
                .andExpect(flash().attributeExists("messages"));
        Member m = members.findByUsername("hong_1").orElseThrow();
        assertThat(m.isCustomer()).isTrue();
        assertThat(m.getContact()).isEqualTo("010-1234-5678");
        assertThat(m.getPasswordHash()).isNotEqualTo(PW);
        assertThat(passwordEncoder.matches(PW, m.getPasswordHash())).isTrue();
    }

    @Test
    void staffSignupRequiresCode() throws Exception {
        mvc.perform(signup("memberType", "STAFF", "staffCode", "wrong"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "staffCode"));
        assertThat(members.count()).isZero();

        mvc.perform(signup("memberType", "STAFF", "staffCode", "TEST-STAFF-CODE"))
                .andExpect(redirectedUrl("/members/login/"));
        assertThat(members.findByUsername("hong_1").orElseThrow().isStaffMember()).isTrue();
    }

    @Test
    void duplicateUsernameIsCaseInsensitive() throws Exception {
        createMember("Hong_1", Role.CUSTOMER);
        mvc.perform(signup()).andExpect(model().attributeHasFieldErrors("form", "username"));
    }

    @ParameterizedTest
    @CsvSource({
            "username, username, ab",
            "contact, contact, 02-123-4567",
            "password2, password2, different",
            "address, address, ''",
    })
    void invalidInputsPointToFields(String errorField, String field, String value) throws Exception {
        mvc.perform(signup(field, value)).andExpect(model().attributeHasFieldErrors("form", errorField));
        assertThat(members.count()).isZero();
    }

    @Test
    void numericShortPasswordIsRejected() throws Exception {
        mvc.perform(signup("password1", "1234", "password2", "1234"))
                .andExpect(model().attributeHasFieldErrors("form", "password1"));
        assertThat(members.count()).isZero();
    }

    @Test
    void dbRejectsSameUsernameWithDifferentCase() {
        createMember("Hong_gd", Role.CUSTOMER);
        assertThatThrownBy(() -> memberService.signUp(Role.CUSTOMER, "HONG_GD", "x", "홍3", "", "010-1111-4444"))
                .isInstanceOf(MemberService.DuplicateAccountException.class);
    }

    /** UC1 5a: 가입 저장 중 DB 오류는 가입 여부를 확인해 안내한다. */
    @Test
    void dbErrorNotSaved() throws Exception {
        doThrow(new DataAccessResourceFailureException("끊김"))
                .when(memberService).signUp(any(), any(), any(), any(), any(), any());
        mvc.perform(signup())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("가입되지 않았습니다")));
        assertThat(members.count()).isZero();
    }

    /** 저장(커밋)은 됐는데 응답 전에 연결이 끊긴 경우. 저장은 별도 트랜잭션으로 먼저 확정한다. */
    @Test
    void dbErrorAfterSaved() throws Exception {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(inv -> {
            committed.execute(s -> {
                try {
                    return inv.callRealMethod();
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
            throw new DataAccessResourceFailureException("응답 전 끊김");
        }).when(memberService).signUp(any(), any(), any(), any(), any(), any());
        MvcResult res = mvc.perform(signup()).andExpect(redirectedUrl("/members/login/")).andReturn();
        mvc.perform(get("/members/login/").flashAttrs(res.getFlashMap()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("가입은 완료되었습니다")));
    }

    // ---------------------------------------------------------------- UC2 로그인

    MvcResult login(MockHttpSession session, String username, String password, String next) throws Exception {
        MockHttpServletRequestBuilder req = post("/members/login/").session(session)
                .param("username", username).param("password", password).with(csrf());
        if (next != null) {
            req.param("next", next);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    void customerLoginOpensHistoryPopupOnce() throws Exception {
        createMember("cust", Role.CUSTOMER);
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "cust", PW, null).getResponse().getRedirectedUrl()).isEqualTo("/");
        mvc.perform(get("/").session(session)).andExpect(model().attribute("showHistoryPopup", true));
        mvc.perform(get("/").session(session)).andExpect(model().attribute("showHistoryPopup", false));
    }

    @Test
    void loginReturnsToNextPageAndKeepsPopupForIt() throws Exception {
        createMember("cust", Role.CUSTOMER);
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "cust", PW, "/credits/").getResponse().getRedirectedUrl()).isEqualTo("/credits/");
        mvc.perform(get("/credits/").session(session)).andExpect(model().attribute("showHistoryPopup", true));
    }

    @Test
    void externalNextIsIgnored() throws Exception {
        createMember("cust", Role.CUSTOMER);
        assertThat(login(new MockHttpSession(), "cust", PW, "//evil.example").getResponse().getRedirectedUrl())
                .isEqualTo("/");
    }

    @Test
    void staffLoginHasNoHistoryPopup() throws Exception {
        createMember("emp", Role.STAFF);
        MockHttpSession session = new MockHttpSession();
        login(session, "emp", PW, null);
        mvc.perform(get("/").session(session))
                .andExpect(model().attribute("showHistoryPopup", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("직원 메뉴")));
    }

    @Test
    void wrongPassword() throws Exception {
        createMember("cust", Role.CUSTOMER);
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "cust", "wrong", null).getResponse().getRedirectedUrl()).isEqualTo("/members/login/");
        mvc.perform(get("/members/login/").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아이디 또는 비밀번호가 올바르지 않습니다")))
                .andExpect(model().attribute("username", "cust"));
        mvc.perform(get("/").session(session)).andExpect(model().attribute("currentMember", (Object) null));
    }

    /** 빈 칸은 브라우저 말풍선이 아니라 화면 안내로 알린다 (폼은 novalidate). */
    @Test
    void blankLoginAsksForInput() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "", "", null);
        mvc.perform(get("/members/login/").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아이디와 비밀번호를 모두 입력해 주세요")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("아이디 또는 비밀번호가 올바르지 않습니다"))));
    }

    @Test
    void logoutReturnsHome() throws Exception {
        createMember("cust", Role.CUSTOMER);
        MockHttpSession session = new MockHttpSession();
        login(session, "cust", PW, null);
        mvc.perform(post("/members/logout/").session(session).with(csrf())).andExpect(redirectedUrl("/"));
    }
}
