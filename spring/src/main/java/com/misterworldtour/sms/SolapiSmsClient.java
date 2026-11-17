package com.misterworldtour.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 솔라피(SOLAPI) 문자 발송 클라이언트 (Django 판 apps/sms/solapi.py).
 *
 * <pre>
 * .env:
 *   SMS_CLIENT=SolapiSMSClient
 *   SOLAPI_API_KEY=...        # 솔라피 콘솔 > API Key 관리
 *   SOLAPI_API_SECRET=...
 *   SOLAPI_SENDER=01012345678 # 솔라피에 등록·인증된 발신번호
 *   SMS_ALLOWED_NUMBERS=010-1111-2222,010-3333-4444  # (선택) 이 번호로만 실제 발송
 * </pre>
 *
 * <ul>
 *   <li>인증: HMAC-SHA256 (서명 대상 = date + salt, 키 = API Secret, 16진수 서명)</li>
 *   <li>발송: POST /messages/v4/send-many/detail. 90byte 를 넘으면 솔라피가 자동으로 LMS 로 보낸다.</li>
 *   <li>연결 실패·HTTP 4xx·접수 거부 → SendException (재시도) / 응답 시간 초과·HTTP 5xx·응답 해석 불가 →
 *       UnknownResultException (재시도 안 함) / 허용 목록 밖 번호 → RejectedException (외부 호출 없음)</li>
 * </ul>
 */
public class SolapiSmsClient implements SmsClient {

    static final String API_URL = "https://api.solapi.com/messages/v4/send-many/detail";
    private static final Logger log = LoggerFactory.getLogger(SolapiSmsClient.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** 실제 HTTP 호출 (테스트에서 바꾼다). */
    interface Transport {
        HttpResponse<String> post(URI uri, String authorization, String body, Duration timeout)
                throws IOException, InterruptedException;
    }

    private final String apiKey;
    private final String apiSecret;
    private final String sender;
    private final Duration timeout;
    private final Set<String> allowed;
    private final Transport transport;
    private final JsonMapper json = JsonMapper.builder().build();

    public SolapiSmsClient(String apiKey, String apiSecret, String sender, Duration timeout, List<String> allowedNumbers) {
        this(apiKey, apiSecret, sender, timeout, allowedNumbers, defaultTransport());
    }

    SolapiSmsClient(String apiKey, String apiSecret, String sender, Duration timeout, List<String> allowedNumbers,
                    Transport transport) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiSecret = apiSecret == null ? "" : apiSecret;
        this.sender = digitsOnly(sender);
        this.timeout = timeout;
        this.allowed = allowedNumbers.stream().map(SolapiSmsClient::digitsOnly).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.transport = transport;
        List<String> missing = new ArrayList<>();
        if (this.apiKey.isEmpty()) {
            missing.add("SOLAPI_API_KEY");
        }
        if (this.apiSecret.isEmpty()) {
            missing.add("SOLAPI_API_SECRET");
        }
        if (this.sender.isEmpty()) {
            missing.add("SOLAPI_SENDER");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("솔라피 문자 설정 누락: " + String.join(", ", missing) + " (.env 확인)");
        }
    }

    private static Transport defaultTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, authorization, body, t) -> http.send(HttpRequest.newBuilder(uri).timeout(t)
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String digitsOnly(String number) {
        return number == null ? "" : number.replaceAll("\\D", "");
    }

    static String authHeader(String apiKey, String apiSecret, String date, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
            return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s".formatted(apiKey, date, salt, signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    /** 접수되면 솔라피 groupId 를 돌려준다 (발송 기록의 업체 접수 번호). */
    @Override
    public String send(String contact, String message) {
        String to = digitsOnly(contact);
        if (!allowed.isEmpty() && !allowed.contains(to)) {
            throw new RejectedException("SMS_ALLOWED_NUMBERS 에 없는 번호라 보내지 않음 (" + contact + ")");
        }
        String body = json.writeValueAsString(Map.of("messages", List.of(Map.of("to", to, "from", sender, "text", message))));
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String auth = authHeader(apiKey, apiSecret, ZonedDateTime.now(ZoneOffset.UTC).format(DATE),
                HexFormat.of().formatHex(saltBytes));

        HttpResponse<String> response;
        try {
            response = transport.post(URI.create(API_URL), auth, body, timeout);
        } catch (HttpTimeoutException e) {
            throw new UnknownResultException("솔라피 응답 시간 초과", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownResultException("솔라피 호출이 중단됨", e);
        } catch (IOException e) {
            throw new SendException("솔라피 연결 실패: " + e.getMessage(), e);
        }
        String detail = response.body() == null ? "" : response.body();
        detail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        if (response.statusCode() >= 500) { // 서버·게이트웨이 오류: 실제로는 접수됐을 수 있어 다시 보내지 않음
            throw new UnknownResultException("솔라피 HTTP " + response.statusCode() + ": " + detail);
        }
        if (response.statusCode() >= 400) { // 요청 거부 (인증 오류, 잔액 부족 등)
            throw new SendException("솔라피 HTTP " + response.statusCode() + ": " + detail);
        }
        JsonNode data;
        try {
            data = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new UnknownResultException("솔라피 응답을 해석할 수 없음", e);
        }
        JsonNode failed = data.path("failedMessageList");
        if (failed.isArray() && !failed.isEmpty()) {
            JsonNode first = failed.get(0);
            throw new SendException("솔라피 접수 실패 " + first.path("statusCode").asString("") + ": "
                    + first.path("statusMessage").asString(""));
        }
        String groupId = data.path("groupInfo").path("groupId").asString("");
        log.info("[SMS 솔라피 접수] to={} group={}", to, groupId);
        return groupId;
    }
}
