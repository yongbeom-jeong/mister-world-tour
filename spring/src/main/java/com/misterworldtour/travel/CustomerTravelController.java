package com.misterworldtour.travel;

import com.misterworldtour.common.Messages;
import com.misterworldtour.common.Messages.Level;
import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.MemberPrincipal;
import com.misterworldtour.travel.BookingService.ApplicationResult;
import com.misterworldtour.travel.ConfigurationRules.ConfigurationException;
import com.misterworldtour.travel.Review.TravelerInput;
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

/** 고객 화면: 상품 선택·구성(UC4)과 여행 신청(UC5). 설계 UI 5장 TravelProductView, 6장 TravelApplicationView. */
@Controller
@RequestMapping("/travel")
@CustomerRequired
class CustomerTravelController {

    /** 신청 화면으로 넘긴 상품·구성·입력 중인 여행자. */
    static final String DRAFT_KEY = "application_draft";
    /** 요청 키별 검토 내용. */
    static final String REVIEWS_KEY = "application_reviews";
    static final int MAX_REVIEWS = 10;
    static final int MAX_TRAVELER_ROWS = 10;
    private static final Logger log = LoggerFactory.getLogger(CustomerTravelController.class);

    /** 세션에 두는 선택 상품·구성·입력 중인 여행자. */
    record Draft(long productId, TravelConfig config, List<TravelerInput> travelers) implements Serializable {
    }

    /** 화면 선택지 하나: 값, 이름, 기본 구성 대비 1인 금액 차이 문구('기본', '+150,000원', '−200,000원'). */
    record OptionChoice(String value, String label, String extra) {
        public String direction() {
            return extra.startsWith("+") ? "up" : extra.startsWith("−") ? "down" : "";
        }
    }

    record DrinkChoice(String value, String label, int price) {
    }

    record OptionField(String field, String title, List<OptionChoice> choices) {
    }

    private final BookingService booking;
    private final JsonMapper json;

    CustomerTravelController(BookingService booking, JsonMapper json) {
        this.booking = booking;
        this.json = json;
    }

    // ---------------------------------------------------------------- UC4 상품 목록

    @GetMapping("/products/")
    String productBrowse(@RequestParam(required = false) String theme,
                         @RequestParam(name = "tour_level", required = false) String tourLevel,
                         Model model, HttpServletResponse response) {
        Theme t = Theme.parse(theme);          // 잘못된 값은 조건 없음으로 (Django 판 폼 검사와 같음)
        TourLevel lv = TourLevel.parse(tourLevel);
        String notice = null;
        boolean loadFailed = false;
        List<TravelProduct> products = List.of();
        if (t != null && t.isLevelRestricted() && lv != null && !TravelRules.ALLOWED_LEVELS_FOR_RESTRICTED.contains(lv)) {
            notice = t.getLabel() + "는 그랜드 또는 프리미엄 등급으로만 운영합니다.";
        } else {
            try {
                products = booking.bookableProducts(t, lv);
            } catch (DataAccessException e) {  // 조회 실패를 '상품 없음'으로 보이지 않게 구분 (UC4 2a·5a)
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

    // ---------------------------------------------------------------- UC4 상품 선택·구성

    /** 화면에 보여줄 선택지와 기본 구성 대비 1인 추가금. */
    private static List<OptionField> optionFields(TravelProduct product) {
        Theme theme = product.getTheme();
        return List.of(
                new OptionField(TravelConfig.HOTEL, "호텔", ConfigurationRules.customerHotelChoices(theme).stream()
                        .map(v -> choice(product, TravelConfig.HOTEL, v.name(), v.getLabel(), v == product.getHotelLevel()))
                        .toList()),
                new OptionField(TravelConfig.TRANSPORT, "교통", ConfigurationRules.customerTransportChoices(theme).stream()
                        .map(v -> choice(product, TravelConfig.TRANSPORT, v.name(), v.getLabel(), v == product.getTransport()))
                        .toList()),
                new OptionField(TravelConfig.MEAL, "식사", ConfigurationRules.customerMealChoices(theme).stream()
                        .map(v -> choice(product, TravelConfig.MEAL, v.name(), v.getLabel(), v == product.getMeal()))
                        .toList()));
    }

    private static OptionChoice choice(TravelProduct product, String field, String value, String label, boolean isDefault) {
        if (isDefault) {
            return new OptionChoice(value, label, "기본");
        }
        int d = Pricing.optionDifference(product, field, value);
        String extra = d == 0 ? "추가금 없음" : (d > 0 ? "+" : "−") + "%,d원".formatted(Math.abs(d));
        return new OptionChoice(value, label, extra);
    }

    @GetMapping("/products/{productId}/")
    String productDetail(@PathVariable long productId, HttpSession session, Model model, RedirectAttributes redirect) {
        TravelProduct product;
        try {
            product = booking.getBookableProduct(productId);
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }
        TravelConfig config = TravelConfig.defaultOf(product);
        // 같은 상품을 다시 열면 직전에 고른 구성을 이어서 보여준다
        if (session.getAttribute(DRAFT_KEY) instanceof Draft draft && draft.productId() == product.getId()) {
            config = draft.config();
        }
        Map<String, List<String>> included = new LinkedHashMap<>();
        TravelRules.INCLUDED_DRINKS.forEach((meal, drinks) ->
                included.put(meal.name(), drinks.stream().map(Enum::name).sorted().toList()));
        model.addAttribute("product", product);
        model.addAttribute("config", config);
        model.addAttribute("configJson", json.writeValueAsString(config.toMap()));
        model.addAttribute("unitPrice", booking.calculateUnitPrice(product, config));
        model.addAttribute("optionFields", optionFields(product));
        model.addAttribute("drinkChoices", Arrays.stream(DrinkOption.values())
                .map(d -> new DrinkChoice(d.name(), d.getLabel(), Pricing.DRINK_PRICES.get(d.name()))).toList());
        model.addAttribute("noDrinkMeals", TravelRules.NO_DRINK_MEALS.stream().map(Enum::name).sorted().toList());
        model.addAttribute("includedDrinks", included);
        model.addAttribute("remaining", booking.remainingSeats(product));
        model.addAttribute("souvenirs", booking.themeSouvenirNames(product));
        model.addAttribute("bodyClass", "product-detail-page");
        return "travel/customer/product_detail";
    }

    /** ConfigureTravelProduct: 현재 구성 + 변경 항목 → 새 구성·1인 가격 (JSON). */
    @PostMapping("/products/{productId}/configure/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> configure(@PathVariable long productId, @RequestBody(required = false) String body) {
        TravelProduct product;
        Map<String, Object> current;
        Map<String, Object> changes;
        try {
            product = booking.getBookableProduct(productId);
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
            BookingService.Configured result = booking.configureTravelProduct(product, current, changes);
            return ResponseEntity.ok(Map.of("ok", true, "config", result.config().toMap(),
                    "described", ConfigurationRules.describe(result.config()), "unit_price", result.unitPrice()));
        } catch (ConfigurationException e) {
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

    /** 선택한 상품·구성을 신청 화면으로 넘긴다 (ShowApplication). 구성은 서버가 다시 검증한다. */
    @PostMapping("/products/{productId}/apply/")
    String startApplication(@PathVariable long productId, @RequestParam(required = false) String config,
                            HttpSession session, RedirectAttributes redirect) {
        TravelProduct product;
        try {
            product = booking.getBookableProduct(productId);
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }
        TravelConfig chosen;
        try {
            Map<String, Object> current = asMap(json.readValue(config == null || config.isBlank() ? "{}" : config,
                    new TypeReference<Map<String, Object>>() { }));
            chosen = ConfigurationRules.applyChanges(product, current, Map.of());
        } catch (JacksonException | ClassCastException | ConfigurationException e) {
            Messages.flash(redirect, Level.ERROR, "여행 구성을 다시 확인해 주세요.");
            return "redirect:/travel/products/" + productId + "/";
        }
        session.setAttribute(DRAFT_KEY, new Draft(product.getId(), chosen, List.of()));
        return "redirect:/travel/apply/";
    }

    // ---------------------------------------------------------------- UC5 신청

    private static List<TravelerInput> travelerRows(List<String> names, List<String> contacts) {
        List<String> n = names == null ? List.of() : names.subList(0, Math.min(names.size(), MAX_TRAVELER_ROWS));
        List<String> c = contacts == null ? List.of() : contacts;
        List<TravelerInput> rows = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) {
            rows.add(new TravelerInput(n.get(i), i < c.size() ? c.get(i) : "", i == 0));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Review> reviews(HttpSession session) {
        Object o = session.getAttribute(REVIEWS_KEY);
        return o instanceof LinkedHashMap<?, ?> m ? (LinkedHashMap<String, Review>) m : new LinkedHashMap<>();
    }

    private static void saveReview(HttpSession session, Review review) {
        LinkedHashMap<String, Review> reviews = reviews(session);
        reviews.put(review.requestKey(), review);
        while (reviews.size() > MAX_REVIEWS) {
            reviews.remove(reviews.keySet().iterator().next());
        }
        session.setAttribute(REVIEWS_KEY, reviews);
    }

    /** 신청이 저장된 뒤 선택 상품·구성과 그 검토 내용을 세션에서 지운다. */
    private static void forgetReview(HttpSession session, String key) {
        session.removeAttribute(DRAFT_KEY);
        LinkedHashMap<String, Review> reviews = reviews(session);
        reviews.remove(key);
        session.setAttribute(REVIEWS_KEY, reviews);
    }

    @GetMapping("/apply/")
    String applyPage(@AuthenticationPrincipal MemberPrincipal me, HttpSession session, Model model,
                     RedirectAttributes redirect) {
        return apply(me, null, null, null, null, session, model, redirect);
    }

    @PostMapping("/apply/")
    String apply(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String action,
                 @RequestParam(name = "request_key", required = false) String requestKey,
                 @RequestParam(name = "traveler_name", required = false) List<String> names,
                 @RequestParam(name = "traveler_contact", required = false) List<String> contacts,
                 HttpSession session, Model model, RedirectAttributes redirect) {
        long memberId = me.getId();
        if ("submit".equals(action) && !reviews(session).containsKey(requestKey)) {
            Optional<Long> done = booking.findSubmittedApplication(memberId, requestKey);
            if (done.isPresent()) { // 이미 처리된 신청을 다시 제출 (뒤로 가기 후 재제출 등): 기존 신청 안내
                Messages.flash(redirect, Level.INFO, "이미 처리된 신청입니다. 신청 내용을 확인해 주세요.");
                return "redirect:/travel/applications/" + done.get() + "/";
            }
        }
        if (!(session.getAttribute(DRAFT_KEY) instanceof Draft draft)) {
            Messages.flash(redirect, Level.INFO, "먼저 신청할 여행 상품과 구성을 선택해 주세요.");
            return "redirect:/travel/products/";
        }
        TravelProduct product;
        try {
            product = booking.getBookableProduct(draft.productId());
        } catch (BookingException e) {
            session.removeAttribute(DRAFT_KEY);
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }

        model.addAttribute("product", product);
        model.addAttribute("described", ConfigurationRules.describe(draft.config()));
        model.addAttribute("unitPrice", booking.calculateUnitPrice(product, draft.config()));
        model.addAttribute("remaining", booking.remainingSeats(product));
        model.addAttribute("travelers", draft.travelers());
        model.addAttribute("errors", Map.of());
        model.addAttribute("review", null);
        model.addAttribute("me", booking.applicantContact(memberId));

        if ("review".equals(action)) {
            List<TravelerInput> rows = travelerRows(names, contacts);
            session.setAttribute(DRAFT_KEY, new Draft(draft.productId(), draft.config(), rows));
            model.addAttribute("travelers", rows);
            try {
                Review review = booking.reviewApplication(memberId, product.getId(), draft.config(), rows);
                saveReview(session, review);
                model.addAttribute("review", review);
            } catch (BookingException e) {
                Messages.now(model, Level.ERROR, e.getMessage());
                model.addAttribute("errors", e.getErrors());
            } catch (DataAccessException e) { // 가격·할인 계산 실패 (UC5 4a): 임의 금액을 쓰지 않고 다시 확인하게 한다
                log.error("신청 금액 계산 중 DB 오류 member={} product={}", memberId, product.getId(), e);
                Messages.now(model, Level.ERROR, "일시적인 오류로 신청 금액을 계산하지 못했습니다. "
                        + "입력한 여행자 정보는 그대로 두었으니 잠시 후 '금액 확인'을 다시 눌러 주세요.");
            }
        } else if ("submit".equals(action)) {
            Review review = reviews(session).get(requestKey);
            if (review == null) {
                Messages.now(model, Level.ERROR, "검토 내용이 만료되었습니다. 금액을 다시 확인해 주세요.");
            } else {
                try {
                    ApplicationResult result = booking.submitTravelApplication(memberId, review);
                    forgetReview(session, requestKey);
                    flashResult(redirect, result);
                    return "redirect:/travel/applications/" + result.applicationId() + "/";
                } catch (BookingException e) {
                    Messages.now(model, Level.ERROR, e.getMessage());
                    model.addAttribute("errors", e.getErrors());
                } catch (DataAccessException e) {
                    log.error("신청 등록 중 DB 오류 member={} key={}", memberId, requestKey, e);
                    Optional<Optional<Long>> done = findAfterDbError(memberId, requestKey);
                    if (done.isPresent() && done.get().isPresent()) { // 저장은 끝났고 그 뒤에 오류
                        forgetReview(session, requestKey);
                        Messages.flash(redirect, Level.WARNING, "처리 중 오류가 있었지만 신청은 저장되었습니다. 신청 내용을 확인해 주세요.");
                        return "redirect:/travel/applications/" + done.get().get() + "/";
                    }
                    if (done.isPresent()) {
                        Messages.now(model, Level.ERROR, "일시적인 오류로 신청이 저장되지 않았습니다. 잠시 후 '신청하기'를 다시 눌러 주세요.");
                    } else {
                        Messages.now(model, Level.ERROR, "일시적인 오류로 신청 반영 여부를 확인하지 못했습니다. "
                                + "잠시 후 '내 신청'에서 확인하고, 없으면 다시 신청해 주세요.");
                    }
                    model.addAttribute("review", review); // 같은 요청 키로 다시 제출 (저장됐다면 중복 없이 기존 신청 안내)
                }
            }
        }
        return "travel/customer/apply";
    }

    /** DB 오류 뒤 반영 여부 확인. 바깥 empty = 확인 불가, 안쪽 empty = 저장 안 됨. */
    private Optional<Optional<Long>> findAfterDbError(long memberId, String key) {
        try {
            return Optional.of(booking.findSubmittedApplication(memberId, key));
        } catch (DataAccessException e) {
            log.error("신청 반영 여부 확인 실패 member={} key={}", memberId, key, e);
            return Optional.empty();
        }
    }

    private static void flashResult(RedirectAttributes redirect, ApplicationResult result) {
        if (result.duplicate()) {
            Messages.flash(redirect, Level.INFO, "이미 처리된 신청입니다. 같은 신청을 다시 등록하지 않았습니다.");
            return;
        }
        Messages.flash(redirect, Level.SUCCESS, "여행 신청이 완료되었습니다.");
        if (result.honeymoon()) {
            Messages.flash(redirect, Level.SUCCESS, "허니문 2인 신청으로 출발이 확정되었습니다.");
        } else if (result.newlyConfirmed()) {
            Messages.flash(redirect, Level.SUCCESS, "이번 신청으로 출발 인원(3명)이 채워져 출발이 확정되었습니다!");
        } else if (result.departureStatus() == DepartureStatus.CONFIRMED) {
            Messages.flash(redirect, Level.SUCCESS, "이미 출발이 확정된 상품입니다.");
        } else {
            Messages.flash(redirect, Level.INFO, "아직 출발 인원(3명)이 모이지 않아 출발 미확정 상태입니다. 확정되면 문자로 알려 드립니다.");
        }
        // TODO(문자 이식 때): 출발 확정 문자 결과 안내
    }

    @GetMapping("/applications/{applicationId}/")
    String applicationDetail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long applicationId, Model model) {
        TravelApplication app = booking.getMyApplication(me.getId(), applicationId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "신청을 찾을 수 없습니다."));
        TravelConfig config = new TravelConfig(app.getHotelLevel().name(), app.getTransport().name(),
                app.getMeal().name(), TravelConfig.normalizeDrinks(app.getExtraDrinks()));
        model.addAttribute("app", app);
        model.addAttribute("travelers", app.getTravelers());
        model.addAttribute("described", ConfigurationRules.describe(config));
        model.addAttribute("status", app.currentDepartureStatus());
        return "travel/customer/application_detail";
    }

    @GetMapping("/applications/")
    String myApplications(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("applications", booking.myApplications(me.getId()));
        return "travel/customer/my_applications";
    }
}
