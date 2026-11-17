package com.misterworldtour.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 예상하지 못한 서버 오류는 기본(Whitelabel) 페이지가 아니라 사이트 디자인의 공통 오류 화면으로 안내한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ErrorPageTest.Endpoints.class)
class ErrorPageTest {

    @TestConfiguration
    static class Endpoints {
        @RestController
        static class Boom {
            /** /members/** 는 로그인 없이 열리는 경로라 시험용 오류 주소로 쓴다. */
            @GetMapping("/members/test-boom")
            String boom() {
                throw new IllegalStateException("시험용 오류");
            }
        }
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void serverErrorShowsSitePage() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/members/test-boom"))
                        .header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).contains("일시적인 오류가 발생했습니다").contains("다시 시도").doesNotContain("Whitelabel");
    }
}
