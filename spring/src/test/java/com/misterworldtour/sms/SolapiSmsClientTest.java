package com.misterworldtour.sms;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 솔라피 클라이언트 (외부 호출 없이 Transport 를 바꿔 확인, Django 판 SolapiClientTests). */
class SolapiSmsClientTest {

    static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public String body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create(SolapiSmsClient.API_URL); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    static SolapiSmsClient client(SolapiSmsClient.Transport t, List<String> allowed) {
        return new SolapiSmsClient("key", "secret", "010-9999-0000", Duration.ofSeconds(10), allowed, t);
    }

    @Test
    void authHeaderSignature() {
        // openssl dgst -sha256 -hmac my-secret <<< "2026-01-01T00:00:00Zabc123" 로 구한 값
        assertThat(SolapiSmsClient.authHeader("k", "my-secret", "2026-01-01T00:00:00Z", "abc123")).isEqualTo(
                "HMAC-SHA256 apiKey=k, date=2026-01-01T00:00:00Z, salt=abc123, "
                        + "signature=94219177911a3c4dada8e9aa05b8187c97eb322880bf34244d1f30acbb1c2866");
    }

    @Test
    void sendsDigitsOnlyNumbersAndReturnsGroupId() {
        AtomicReference<String> sent = new AtomicReference<>();
        SolapiSmsClient c = client((uri, auth, body, t) -> {
            sent.set(body);
            assertThat(auth).startsWith("HMAC-SHA256 apiKey=key, date=");
            return response(200, "{\"groupInfo\":{\"groupId\":\"G123\"},\"failedMessageList\":[]}");
        }, List.of());
        assertThat(c.send("010-1234-5678", "안녕")).isEqualTo("G123");
        assertThat(sent.get()).contains("\"to\":\"01012345678\"").contains("\"from\":\"01099990000\"");
    }

    @Test
    void failedMessageListIsSendError() {
        SolapiSmsClient c = client((u, a, b, t) -> response(200,
                "{\"failedMessageList\":[{\"statusCode\":\"1062\",\"statusMessage\":\"잘못된 번호\"}]}"), List.of());
        assertThatThrownBy(() -> c.send("010", "x")).isInstanceOf(SmsClient.SendException.class).hasMessageContaining("1062");
    }

    @Test
    void httpErrorIsSendErrorAndServerErrorIsUnknown() {
        assertThatThrownBy(() -> client((u, a, b, t) -> response(401, "unauthorized"), List.of()).send("010", "x"))
                .isInstanceOf(SmsClient.SendException.class);
        assertThatThrownBy(() -> client((u, a, b, t) -> response(502, "bad gateway"), List.of()).send("010", "x"))
                .isInstanceOf(SmsClient.UnknownResultException.class);
        assertThatThrownBy(() -> client((u, a, b, t) -> response(200, "not json"), List.of()).send("010", "x"))
                .isInstanceOf(SmsClient.UnknownResultException.class);
    }

    @Test
    void timeoutIsUnknownAndConnectionErrorIsSendError() {
        assertThatThrownBy(() -> client((u, a, b, t) -> {
            throw new HttpTimeoutException("timeout");
        }, List.of()).send("010", "x")).isInstanceOf(SmsClient.UnknownResultException.class);
        assertThatThrownBy(() -> client((u, a, b, t) -> {
            throw new IOException("connection refused");
        }, List.of()).send("010", "x")).isInstanceOf(SmsClient.SendException.class)
                .isNotInstanceOf(SmsClient.RejectedException.class);
    }

    @Test
    void allowedNumbersBlockOthersWithoutNetwork() {
        SolapiSmsClient c = client((u, a, b, t) -> {
            throw new AssertionError("외부 호출하면 안 됨");
        }, List.of("010-1111-2222"));
        assertThatThrownBy(() -> c.send("010-3333-4444", "x")).isInstanceOf(SmsClient.RejectedException.class);
    }

    @Test
    void missingSettings() {
        assertThatThrownBy(() -> new SolapiSmsClient("", "", "", Duration.ofSeconds(1), List.of()))
                .hasMessageContaining("SOLAPI_API_KEY").hasMessageContaining("SOLAPI_SENDER");
    }
}
