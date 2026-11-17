package com.misterworldtour.ui;

import com.misterworldtour.members.CustomerRequired;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.travel.BookingException;
import com.misterworldtour.travel.DepartureStatus;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelApplication;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.travel.TravelProduct;
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

/**
 * 고객의 여행 신청 화면 (설계 3.1 TravelApplicationView, UC5, 상태도 11.2).
 * 상품 화면에서 넘긴 상품·구성(ShowApplication)에 여행자를 입력해 검토(ReviewApplication)하고, 확인하면 등록한다
 * (SubmitTravelApplication). 검토 결과는 요청 키별로 세션에 두어 같은 키로만 다시 제출한다.
 * 설계 이후 추가: 내 신청 목록·상세, 출발 확정 전 신청 취소.
 */
@Controller
@CustomerRequired
public class TravelApplicationView {

    /** 신청 화면으로 넘긴 상품·구성·입력 중인 여행자. */
    static final String DRAFT_KEY = "application_draft";
    /** 요청 키별 검토 내용. */
    static final String REVIEWS_KEY = "application_reviews";
    static final int MAX_REVIEWS = 10;
    static final int MAX_TRAVELER_ROWS = 10;

    /** 세션에 두는 선택 상품·구성·입력 중인 여행자. */
    public record Draft(long productId, TravelConfiguration config, List<TravelerInput> travelers) implements Serializable {
    }
    private static final Logger log = LoggerFactory.getLogger(TravelApplicationView.class);

    private final TravelManagementAPI travelManager;

    TravelApplicationView(TravelManagementAPI travelManager) {
        this.travelManager = travelManager;
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
    private static LinkedHashMap<String, ApplicationReviewResult> reviews(HttpSession session) {
        Object o = session.getAttribute(REVIEWS_KEY);
        return o instanceof LinkedHashMap<?, ?> m ? (LinkedHashMap<String, ApplicationReviewResult>) m : new LinkedHashMap<>();
    }

    private static void saveReview(HttpSession session, ApplicationReviewResult review) {
        LinkedHashMap<String, ApplicationReviewResult> reviews = reviews(session);
        reviews.put(review.requestKey(), review);
        while (reviews.size() > MAX_REVIEWS) {
            reviews.remove(reviews.keySet().iterator().next());
        }
        session.setAttribute(REVIEWS_KEY, reviews);
    }

    /** 신청이 저장된 뒤 선택 상품·구성과 그 검토 내용을 세션에서 지운다. */
    private static void forgetReview(HttpSession session, String key) {
        session.removeAttribute(DRAFT_KEY);
        LinkedHashMap<String, ApplicationReviewResult> reviews = reviews(session);
        reviews.remove(key);
        session.setAttribute(REVIEWS_KEY, reviews);
    }

    /** 신청 화면에 보여 줄 선택 상품·구성. redirect 가 있으면 화면 대신 그 주소로 보낸다. */
    private record ApplicationPage(Draft draft, TravelProduct product, String redirect) {
    }

    /** 신청 화면 공통 값을 채운다. 선택한 상품·구성이 없거나 신청할 수 없는 상품이면 상품 목록으로 보낸다. */
    private ApplicationPage page(long memberId, HttpSession session, Model model, RedirectAttributes redirect) {
        if (!(session.getAttribute(DRAFT_KEY) instanceof Draft draft)) {
            Messages.flash(redirect, Level.INFO, "먼저 신청할 여행 상품과 구성을 선택해 주세요.");
            return new ApplicationPage(null, null, "redirect:/travel/products/");
        }
        TravelProduct product;
        try {
            product = travelManager.selectProduct(draft.productId());
        } catch (BookingException e) {
            session.removeAttribute(DRAFT_KEY);
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return new ApplicationPage(null, null, "redirect:/travel/products/");
        }
        model.addAttribute("product", product);
        model.addAttribute("described", draft.config().describe());
        model.addAttribute("unitPrice", travelManager.calculateUnitPrice(product, draft.config()));
        model.addAttribute("remaining", travelManager.remainingSeats(product));
        model.addAttribute("travelers", draft.travelers());
        model.addAttribute("errors", Map.of());
        model.addAttribute("review", null);
        model.addAttribute("me", travelManager.applicantContact(memberId));
        model.addAttribute("cancelRule", TravelManagementAPI.CANCEL_RULE);
        return new ApplicationPage(draft, product, null);
    }

    /** ShowApplication: 상품 화면에서 넘긴 상품·구성으로 여행자 입력을 시작한다 (상태도 11.2 Editing). */
    @GetMapping("/travel/apply/")
    String showApplication(@AuthenticationPrincipal LoginSession me, HttpSession session, Model model,
                           RedirectAttributes redirect) {
        ApplicationPage page = page(me.getMemberId(), session, model, redirect);
        return page.redirect() != null ? page.redirect() : "travel/customer/apply";
    }

    /** 알 수 없는 제출(action 없음)은 입력 화면을 다시 보여 준다. */
    @PostMapping("/travel/apply/")
    String showApplicationAgain(@AuthenticationPrincipal LoginSession me, HttpSession session, Model model,
                                RedirectAttributes redirect) {
        return showApplication(me, session, model, redirect);
    }

    /** ReviewApplication: 여행자를 받아 신청 내용과 최종 금액(단골 할인 포함)을 검토한다 (Reviewing → Reviewed). */
    @PostMapping(value = "/travel/apply/", params = "action=review")
    String reviewApplication(@AuthenticationPrincipal LoginSession me,
                             @RequestParam(name = "traveler_name", required = false) List<String> names,
                             @RequestParam(name = "traveler_contact", required = false) List<String> contacts,
                             HttpSession session, Model model, RedirectAttributes redirect) {
        long memberId = me.getMemberId();
        ApplicationPage page = page(memberId, session, model, redirect);
        if (page.redirect() != null) {
            return page.redirect();
        }
        Draft draft = page.draft();
        TravelProduct product = page.product();
        List<TravelerInput> rows = travelerRows(names, contacts);
        session.setAttribute(DRAFT_KEY, new Draft(draft.productId(), draft.config(), rows));
        model.addAttribute("travelers", rows);
        try {
            ApplicationReviewResult review = travelManager.reviewApplication(product.getProductId(), draft.config(), rows);
            saveReview(session, review);
            model.addAttribute("review", review);
        } catch (BookingException e) {
            Messages.now(model, Level.ERROR, e.getMessage());
            model.addAttribute("errors", e.getErrors());
        } catch (DataAccessException e) { // 가격·할인 계산 실패 (UC5 4a): 임의 금액을 쓰지 않고 다시 확인하게 한다
            log.error("신청 금액 계산 중 DB 오류 member={} product={}", memberId, product.getProductId(), e);
            Messages.now(model, Level.ERROR, "일시적인 오류로 신청 금액을 계산하지 못했습니다. "
                    + "입력한 여행자 정보는 그대로 두었으니 잠시 후 '금액 확인'을 다시 눌러 주세요.");
        }
        return "travel/customer/apply";
    }

    /**
     * SubmitTravelApplication: 검토한 신청을 같은 요청 키로 등록한다 (Submitting → Registered / Outcome Unknown).
     * 저장 여부를 알 수 없으면 같은 요청 키와 신청 내용을 유지해 다시 제출하게 한다.
     */
    @PostMapping(value = "/travel/apply/", params = "action=submit")
    String submitTravelApplication(@AuthenticationPrincipal LoginSession me,
                                   @RequestParam(name = "request_key", required = false) String requestKey,
                                   HttpSession session, Model model, RedirectAttributes redirect) {
        long memberId = me.getMemberId();
        if (!reviews(session).containsKey(requestKey)) {
            Optional<Long> done = travelManager.findSubmittedApplication(memberId, requestKey);
            if (done.isPresent()) { // 이미 처리된 신청을 다시 제출 (뒤로 가기 후 재제출 등): 기존 신청 안내
                Messages.flash(redirect, Level.INFO, "이미 처리된 신청입니다. 신청 내용을 확인해 주세요.");
                return "redirect:/travel/applications/" + done.get() + "/";
            }
        }
        ApplicationPage page = page(memberId, session, model, redirect);
        if (page.redirect() != null) {
            return page.redirect();
        }
        ApplicationReviewResult review = reviews(session).get(requestKey);
        if (review == null) {
            Messages.now(model, Level.ERROR, "검토 내용이 만료되었습니다. 금액을 다시 확인해 주세요.");
            return "travel/customer/apply";
        }
        try {
            ApplicationResult result = travelManager.submitTravelApplication(review.productId(),
                    review.configuration(), review.travelers(), review.requestKey(), review.price());
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
        return "travel/customer/apply";
    }

    /** DB 오류 뒤 반영 여부 확인. 바깥 empty = 확인 불가, 안쪽 empty = 저장 안 됨. */
    private Optional<Optional<Long>> findAfterDbError(long memberId, String key) {
        try {
            return Optional.of(travelManager.findSubmittedApplication(memberId, key));
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
        if (result.sms() != null) {
            boolean problem = result.sms().failed() > 0 || result.sms().unknown() > 0;
            Messages.flash(redirect, problem ? Level.WARNING : Level.INFO, result.sms().summary()
                    + (problem ? " (신청과 출발 확정은 정상 저장되었습니다.)" : ""));
        }
    }

    @GetMapping("/travel/applications/{applicationId}/")
    String applicationDetail(@AuthenticationPrincipal LoginSession me, @PathVariable long applicationId, Model model) {
        TravelApplication app = travelManager.getMyApplication(me.getMemberId(), applicationId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "신청을 찾을 수 없습니다."));
        model.addAttribute("app", app);
        model.addAttribute("travelers", app.getTravelers());
        model.addAttribute("described", app.getConfiguration().describe());
        model.addAttribute("status", app.currentDepartureStatus());
        model.addAttribute("cancelBlock", travelManager.cancelBlockReason(app));
        model.addAttribute("cancelRule", TravelManagementAPI.CANCEL_RULE);
        return "travel/customer/application_detail";
    }

    /** 출발 확정 전 신청 취소 (2026-11-12 결정). */
    @PostMapping("/travel/applications/{applicationId}/cancel/")
    String cancelApplication(@AuthenticationPrincipal LoginSession me, @PathVariable long applicationId,
                             RedirectAttributes redirect) {
        if (travelManager.getMyApplication(me.getMemberId(), applicationId).isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "신청을 찾을 수 없습니다.");
        }
        try {
            travelManager.cancelApplication(me.getMemberId(), applicationId);
            Messages.flash(redirect, Level.SUCCESS, "신청을 취소했습니다.");
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
        }
        return "redirect:/travel/applications/" + applicationId + "/";
    }

    @GetMapping("/travel/applications/")
    String myApplications(@AuthenticationPrincipal LoginSession me, Model model) {
        model.addAttribute("applications", travelManager.myApplications(me.getMemberId()));
        return "travel/customer/my_applications";
    }
}
