package com.misterworldtour.speech;

import com.misterworldtour.travel.DrinkOption;
import com.misterworldtour.travel.HotelLevel;
import com.misterworldtour.travel.MealOption;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelPriceCalculator;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * OpenAI 채팅 API 로 하는 음성 예약 대화. 시스템 프롬프트(speech/voice-dialog-prompt.md, 시나리오 예시 포함)와
 * 상품 목록·현재 값을 보내고, JSON 스키마(structured outputs)로 대사와 값을 받는다.
 * 실패(연결·시간 초과·HTTP 오류·거절)는 예외로 알리고, {@link VoiceBookingManager} 가 그 턴을 규칙 대화로 답한다.
 */
public class OpenAIDialogClient implements VoiceDialogClient {

    static final String PROMPT_PATH = "speech/voice-dialog-prompt.md";

    interface Transport {
        HttpResponse<String> post(URI uri, String apiKey, String body, Duration timeout)
                throws IOException, InterruptedException;
    }

    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final Duration timeout;
    private final Transport transport;
    private final String systemPrompt;
    private final JsonMapper json = JsonMapper.builder().build();

    public OpenAIDialogClient(String apiKey, String model, String baseUrl, Duration timeout) {
        this(apiKey, model, baseUrl, timeout, defaultTransport());
    }

    OpenAIDialogClient(String apiKey, String model, String baseUrl, Duration timeout, Transport transport) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.timeout = timeout;
        this.transport = transport;
        this.systemPrompt = loadPrompt();
    }

    static String loadPrompt() {
        try (InputStream in = new ClassPathResource(PROMPT_PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("음성 예약 프롬프트를 읽지 못했습니다: " + PROMPT_PATH, e);
        }
    }

    private static Transport defaultTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, key, body, t) -> http.send(HttpRequest.newBuilder(uri).timeout(t)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public DialogReply reply(DialogRequest req) {
        String body = json.writeValueAsString(requestBody(req));
        HttpResponse<String> response;
        try {
            response = transport.post(endpoint, apiKey, body, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 호출이 중단됨", e);
        } catch (IOException e) {
            throw new UncheckedIOException("OpenAI 연결 실패·시간 초과: " + e.getMessage(), e);
        }
        String text = response.body() == null ? "" : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OpenAI HTTP " + response.statusCode() + ": "
                    + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        JsonNode message = json.readTree(text).path("choices").path(0).path("message");
        if (!message.path("refusal").isNull() && !message.path("refusal").isMissingNode()) {
            throw new IllegalStateException("OpenAI 응답 거절: " + message.path("refusal").asString(""));
        }
        return parseReply(json.readTree(message.path("content").asString("{}")));
    }

    Map<String, Object> requestBody(DialogRequest req) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "system", "content", "[대화 정보]\n" + json.writeValueAsString(context(req))));
        for (Turn turn : req.history()) {
            messages.add(Map.of("role", Turn.CUSTOMER.equals(turn.role()) ? "user" : "assistant", "content", turn.text()));
        }
        messages.add(Map.of("role", "user", "content", req.utterance()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema",
                Map.of("name", "voice_booking_turn", "strict", true, "schema", schema())));
        return body;
    }

    /** 1인 추가 금액. 호텔·식사는 상품 기본값과의 차액만 붙는다 (TravelPriceCalculator 와 같은 값). */
    static final Map<String, Object> OPTION_PRICES = optionPrices();

    private static Map<String, Object> optionPrices() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("설명", "호텔·식사는 상품 기본값보다 올리면 차액만큼 더하고 내리면 뺀다. 음료는 1인 1세트 추가 금액");
        Map<String, Integer> hotel = TravelPriceCalculator.OPTION_PRICE_TABLE.get(TravelConfiguration.HOTEL);
        Map<String, Integer> meal = TravelPriceCalculator.OPTION_PRICE_TABLE.get(TravelConfiguration.MEAL);
        m.put("호텔", Map.of("STAR3", hotel.get("STAR3"), "STAR4", hotel.get("STAR4"), "STAR5", hotel.get("STAR5")));
        m.put("식사", Map.of("LUNCHBOX", meal.get("LUNCHBOX"), "LOCAL", meal.get("LOCAL"), "FINE", meal.get("FINE")));
        m.put("음료", TravelPriceCalculator.DRINK_PRICES);
        m.put("포함", "고급 레스토랑 식사(FINE)에는 샴페인이 기본 포함(0원)");
        return m;
    }

    private static Map<String, Object> context(DialogRequest req) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("오늘", req.today().toString());
        c.put("고객 이름", req.customerName());
        List<Map<String, Object>> products = new ArrayList<>();
        for (ProductInfo p : req.products()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.productId());
            m.put("이름", p.name());
            m.put("여행지", p.destination());
            m.put("소개", p.description());
            m.put("theme", p.theme());
            m.put("테마", p.themeLabel());
            m.put("tour_level", p.tourLevel());
            m.put("스타일", p.levelLabel());
            m.put("출발일", p.departureDate().toString());
            m.put("기간(일)", p.travelPeriod());
            m.put("1인 기본 가격(원)", p.basePrice());
            m.put("남은 자리", p.remainingSeats() < 0 ? "허니문(2인 신청마다 바로 출발 확정)" : p.remainingSeats());
            products.add(m);
        }
        c.put("신청 가능한 상품", products);
        c.put("옵션 가격", OPTION_PRICES);
        DialogSlots s = req.slots();
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("theme", s.theme());
        now.put("tour_level", s.tourLevel());
        now.put("product_id", s.productId());
        now.put("departure_date", s.departureDate());
        now.put("traveler_count", s.travelerCount());
        now.put("hotel_level", s.hotelLevel());
        now.put("meal", s.meal());
        now.put("drinks", s.drinks());
        c.put("현재 값", now);
        return c;
    }

    private static List<Object> namesOrNull(Enum<?>[] values) {
        List<Object> out = new ArrayList<>(Arrays.stream(values).map(Enum::name).toList());
        out.add(null);
        return out;
    }

    private static Map<String, Object> nullableEnum(Enum<?>[] values) {
        return Map.of("type", List.of("string", "null"), "enum", namesOrNull(values));
    }

    static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reply", Map.of("type", "string", "description", "고객에게 소리 내어 읽어 줄 대사"));
        props.put("theme", nullableEnum(Theme.values()));
        props.put("tour_level", nullableEnum(TourLevel.values()));
        props.put("product_id", Map.of("type", List.of("integer", "null")));
        props.put("departure_date", Map.of("type", List.of("string", "null"), "description", "원하는 출발일 yyyy-MM-dd"));
        props.put("traveler_count", Map.of("type", List.of("integer", "null")));
        props.put("hotel_level", nullableEnum(HotelLevel.values()));
        props.put("meal", nullableEnum(MealOption.values()));
        props.put("drinks", Map.of("type", "array", "items", Map.of("type", "string", "enum",
                Stream.of(DrinkOption.values()).map(Enum::name).toList())));
        props.put("action", Map.of("type", "string", "enum", List.of("ASK", "BOOK")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new ArrayList<>(props.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String text(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asString(null);
    }

    static DialogReply parseReply(JsonNode out) {
        List<String> drinks = new ArrayList<>();
        out.path("drinks").forEach(d -> drinks.add(d.asString("")));
        JsonNode pid = out.path("product_id");
        JsonNode count = out.path("traveler_count");
        DialogSlots slots = new DialogSlots(text(out.path("theme")), text(out.path("tour_level")),
                pid.isNumber() ? pid.asLong() : null, text(out.path("departure_date")),
                count.isNumber() ? count.asInt() : null, text(out.path("hotel_level")), text(out.path("meal")),
                drinks, null);
        Action action = "BOOK".equals(out.path("action").asString("")) ? Action.BOOK : Action.ASK;
        return new DialogReply(out.path("reply").asString(""), slots, action);
    }
}
