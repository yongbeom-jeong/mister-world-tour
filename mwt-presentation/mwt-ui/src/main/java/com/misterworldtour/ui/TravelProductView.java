package com.misterworldtour.ui;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.speech.SpeechRecognitionAPI.ProductCandidate;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionContext;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionResult;
import com.misterworldtour.speech.SpeechRecognitionAPI;
import com.misterworldtour.speech.SpeechServiceClient;
import com.misterworldtour.travel.BookingException;
import com.misterworldtour.travel.DrinkOption;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelManagementAPI.ConfigurationResult;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.travel.TravelPriceCalculator;
import com.misterworldtour.travel.TravelProduct;
import com.misterworldtour.travel.TravelRules;
import com.misterworldtour.ui.Messages.Level;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 고객 여행 상품 선택·구성 화면 (설계 3.1 TravelProductView, UC4). */
@Controller
@CustomerRequired
public class TravelProductView {

    private static final Logger log = LoggerFactory.getLogger(TravelProductView.class);

    /** 선택지: 값, 이름, 기본 구성 대비 1인 금액 차이 문구. */
    record OptionChoice(String value, String label, String extra) {
        public String direction() {
            return extra.startsWith("+") ? "up" : extra.startsWith("−") ? "down" : "";
        }
    }

    record DrinkChoice(String value, String label, int price) {
    }

    record OptionField(String field, String title, List<OptionChoice> choices) {
    }

    private final TravelManagementAPI travelManager;
    private final SpeechRecognitionAPI speechRecognition;
    private final JsonMapper json;

    TravelProductView(TravelManagementAPI travelManager, SpeechRecognitionAPI speechRecognition, JsonMapper json) {
        this.travelManager = travelManager;
        this.speechRecognition = speechRecognition;
        this.json = json;
    }

    /** LoadProducts */
    @GetMapping("/travel/products/")
    String loadProducts(@RequestParam(required = false) String theme,
                         @RequestParam(name = "tour_level", required = false) String tourLevel,
                         Model model, HttpServletResponse response) {
        Theme t = Theme.parse(theme);
        TourLevel lv = TourLevel.parse(tourLevel);
        String notice = null;
        boolean loadFailed = false;
        List<TravelProduct> products = List.of();
        if (t != null && t.isLevelRestricted() && lv != null && !TravelRules.ALLOWED_LEVELS_FOR_RESTRICTED.contains(lv)) {
            notice = t.getLabel() + "는 그랜드 또는 프리미엄 등급으로만 운영합니다.";
        } else {
            try {
                products = travelManager.loadProducts(t, lv);
            } catch (DataAccessException e) {  // '상품 없음'과 구분 (UC4 2a·5a)
                log.error("상품 목록 조회 실패 theme={} level={}", t, lv, e);
                loadFailed = true;
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        }
        model.addAttribute("products", products);
        model.addAttribute("notice", notice);
        model.addAttribute("theme", t);
        model.addAttribute("level", lv);
        model.addAttribute("themes", Theme.values());
        model.addAttribute("loadFailed", loadFailed);
        model.addAttribute("filtered", t != null || lv != null);
        return "travel/customer/products";
    }

    private static List<OptionField> optionFields(TravelProduct product) {
        Theme theme = product.getTheme();
        return List.of(
                new OptionField(TravelConfiguration.HOTEL, "호텔", TravelConfiguration.customerHotelChoices(theme).stream()
                        .map(v -> choice(product, TravelConfiguration.HOTEL, v.name(), v.getLabel(), v == product.getHotelLevel()))
                        .toList()),
                new OptionField(TravelConfiguration.TRANSPORT, "교통", TravelConfiguration.customerTransportChoices(theme).stream()
                        .map(v -> choice(product, TravelConfiguration.TRANSPORT, v.name(), v.getLabel(), v == product.getTransport()))
                        .toList()),
                new OptionField(TravelConfiguration.MEAL, "식사", TravelConfiguration.customerMealChoices(theme).stream()
                        .map(v -> choice(product, TravelConfiguration.MEAL, v.name(), v.getLabel(), v == product.getMeal()))
                        .toList()));
    }

    private static OptionChoice choice(TravelProduct product, String field, String value, String label, boolean isDefault) {
        if (isDefault) {
            return new OptionChoice(value, label, "기본");
        }
        int d = TravelPriceCalculator.optionDifference(product, field, value);
        String extra = d == 0 ? "추가금 없음" : (d > 0 ? "+" : "−") + "%,d원".formatted(Math.abs(d));
        return new OptionChoice(value, label, extra);
    }

    /** SelectProduct */
    @GetMapping("/travel/products/{productId}/")
    String selectProduct(@PathVariable long productId, HttpSession session, Model model, RedirectAttributes redirect) {
        TravelProduct product;
        try {
            product = travelManager.selectProduct(productId);
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }
        TravelConfiguration config = TravelConfiguration.defaultOf(product);
        // 같은 상품을 다시 열면 직전에 고른 구성을 이어서 보여준다
        if (session.getAttribute(TravelApplicationView.DRAFT_KEY) instanceof TravelApplicationView.Draft draft && draft.productId() == product.getProductId()) {
            config = draft.config();
        }
        Map<String, List<String>> included = new LinkedHashMap<>();
        TravelRules.INCLUDED_DRINKS.forEach((meal, drinks) ->
                included.put(meal.name(), drinks.stream().map(Enum::name).sorted().toList()));
        model.addAttribute("product", product);
        model.addAttribute("config", config);
        model.addAttribute("configJson", json.writeValueAsString(config.toMap()));
        model.addAttribute("unitPrice", travelManager.calculateUnitPrice(product, config));
        model.addAttribute("optionFields", optionFields(product));
        model.addAttribute("drinkChoices", Arrays.stream(DrinkOption.values())
                .map(d -> new DrinkChoice(d.name(), d.getLabel(), TravelPriceCalculator.DRINK_PRICES.get(d.name()))).toList());
        model.addAttribute("noDrinkMeals", TravelRules.NO_DRINK_MEALS.stream().map(Enum::name).sorted().toList());
        model.addAttribute("includedDrinks", included);
        model.addAttribute("remaining", travelManager.remainingSeats(product));
        model.addAttribute("souvenirs", travelManager.themeSouvenirNames(product));
        model.addAttribute("bodyClass", "product-detail-page");
        return "travel/customer/product_detail";
    }

    /** ConfigureTravelProduct (JSON) */
    @PostMapping("/travel/products/{productId}/configure/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> configureTravelProduct(@PathVariable long productId, @RequestBody(required = false) String body) {
        TravelProduct product;
        Map<String, Object> current;
        Map<String, Object> changes;
        try {
            product = travelManager.selectProduct(productId);
            Map<String, Object> parsed = json.readValue(body == null || body.isBlank() ? "{}" : body,
                    new TypeReference<Map<String, Object>>() { });
            current = asMap(parsed.get("current"));
            changes = asMap(parsed.get("changes"));
        } catch (BookingException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "message", e.getMessage()));
        } catch (JacksonException | ClassCastException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "잘못된 요청입니다."));
        }
        try {
            ConfigurationResult result = travelManager.configureTravelProduct(product.getProductId(),
                    product.getDefaultConfiguration().applyChanges(current), changes);
            return ResponseEntity.ok(Map.of("ok", true, "config", result.configuration().toMap(),
                    "described", result.configuration().describe(), "unit_price", result.unitPrice()));
        } catch (TravelManagementAPI.ConfigurationException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage(), "errors", e.getErrors()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return Map.of();
        }
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new ClassCastException("not an object");
    }

    /** 신청 화면(ShowApplication)으로 넘긴다. 구성은 서버가 다시 검증한다. */
    @PostMapping("/travel/products/{productId}/apply/")
    String startApplication(@PathVariable long productId, @RequestParam(required = false) String config,
                            HttpSession session, RedirectAttributes redirect) {
        TravelProduct product;
        try {
            product = travelManager.selectProduct(productId);
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }
        TravelConfiguration chosen;
        try {
            Map<String, Object> current = asMap(json.readValue(config == null || config.isBlank() ? "{}" : config,
                    new TypeReference<Map<String, Object>>() { }));
            chosen = travelManager.configureTravelProduct(product.getProductId(), product.getDefaultConfiguration().applyChanges(current),
                    Map.of()).configuration();
        } catch (JacksonException | ClassCastException | TravelManagementAPI.ConfigurationException e) {
            Messages.flash(redirect, Level.ERROR, "여행 구성을 다시 확인해 주세요.");
            return "redirect:/travel/products/" + productId + "/";
        }
        session.setAttribute(TravelApplicationView.DRAFT_KEY, new TravelApplicationView.Draft(product.getProductId(), chosen, List.of()));
        return "redirect:/travel/apply/";
    }

    private Map<String, Object> parseJson(String body) {
        return json.readValue(body == null || body.isBlank() ? "{}" : body, new TypeReference<Map<String, Object>>() { });
    }

    /** SelectByVoice: 인식 문장 + 현재 조건 → 선택 값 (JSON). */
    @PostMapping("/speech/recognize/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> selectByVoice(@RequestBody(required = false) String body) {
        Map<String, Object> data;
        try {
            data = parseJson(body);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "RECOGNITION_FAILED", "message", "잘못된 요청입니다."));
        }
        Theme theme = Theme.parse(String.valueOf(data.get("theme")));
        TourLevel level = TourLevel.parse(String.valueOf(data.get("tour_level")));
        // 문맥의 상품 목록은 서버가 같은 조건으로 다시 조회한다 (화면과 같은 순서)
        List<ProductCandidate> products = travelManager.loadProducts(theme, level).stream()
                .map(p -> new ProductCandidate(p.getProductId(), p.getProductName(), p.getDestination())).toList();
        Object transcript = data.get("transcript");
        VoiceSelectionResult result = speechRecognition.recognizeSelection(
                SpeechServiceClient.VoiceInput.ofTranscript(transcript == null ? "" : transcript.toString()),
                new VoiceSelectionContext(theme, level, products));
        return ResponseEntity.ok(result.toMap());
    }

    /** 브라우저 음성 인식 실패 기록 (UC4 4a·7a). */
    @PostMapping("/speech/failure/")
    @ResponseBody
    ResponseEntity<Void> browserFailure(@AuthenticationPrincipal LoginSession me,
                                        @RequestBody(required = false) String body) {
        String error = "";
        try {
            Object e = parseJson(body).get("error");
            error = e == null ? "" : e.toString();
        } catch (JacksonException ignored) {
        }
        speechRecognition.recordBrowserFailure(error.length() > 40 ? error.substring(0, 40) : error, me.getMemberId());
        return ResponseEntity.noContent().build();
    }
}
