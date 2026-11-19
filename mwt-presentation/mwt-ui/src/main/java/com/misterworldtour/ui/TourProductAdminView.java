package com.misterworldtour.ui;

import com.misterworldtour.members.StaffRoleRequired;
import com.misterworldtour.travel.ProductImages.InvalidImageException;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelManagementAPI.ImageChange;
import com.misterworldtour.travel.TravelManagementAPI.ProductHasApplicationsException;
import com.misterworldtour.travel.TravelManagementAPI.ProductNotFoundException;
import com.misterworldtour.travel.TravelManagementAPI.ProductValidationException;
import com.misterworldtour.travel.TravelManagementAPI;
import com.misterworldtour.travel.TravelProduct;
import com.misterworldtour.ui.Messages.Level;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 직원 상품 등록·관리 화면 (설계 3.1 TourProductAdminView, UC6·UC7). 신청이 있는 상품은 수정·삭제 불가. */
@Controller
@RequestMapping("/travel/staff")
@StaffRoleRequired
public class TourProductAdminView {

    private static final Logger log = LoggerFactory.getLogger(TourProductAdminView.class);

    private final TravelManagementAPI travel;
    private final Clock clock;

    TourProductAdminView(TravelManagementAPI travel, Clock clock) {
        this.travel = travel;
        this.clock = clock;
    }

    @ModelAttribute("themes")
    Theme[] themes() {
        return Theme.values();
    }

    @ModelAttribute("levels")
    TourLevel[] levels() {
        return TourLevel.values();
    }

    private static ImageChange imageChange(MultipartFile image, String clear) {
        if (image != null && !image.isEmpty()) {
            try {
                return ImageChange.replace(image.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return clear != null ? ImageChange.DELETE : ImageChange.KEEP;
    }

    /** ShowRegistration */
    @GetMapping("/products/new/")
    String showRegistration(Model model) {
        model.addAttribute("form", new ProductForm());
        model.addAttribute("errors", Map.of());
        model.addAttribute("minDate", LocalDate.now(clock).plusDays(1));
        return "travel/staff/product_register";
    }

    @PostMapping("/products/new/")
    String registerTourProduct(@ModelAttribute("form") ProductForm form, @RequestParam(required = false) MultipartFile image,
                    Model model, RedirectAttributes redirect) {
        Map<String, String> errors = new LinkedHashMap<>(form.validate());
        if (errors.isEmpty()) {
            OffsetDateTime started = OffsetDateTime.now();
            try {
                TravelProduct p = travel.registerTourProduct(form.input(), imageChange(image, null));
                Messages.flash(redirect, Level.SUCCESS, "'" + p.getProductName() + "' 상품을 등록했습니다.");
                return "redirect:/travel/staff/products/" + p.getProductId() + "/";
            } catch (InvalidImageException e) {
                errors.put("image", e.getMessage());
            } catch (ProductValidationException e) {
                errors.putAll(e.getErrors());
            } catch (DataAccessException e) {
                log.error("상품 등록 중 DB 오류", e);
                Optional<Optional<TravelProduct>> saved = findAfterError(form, started);
                if (saved.isPresent() && saved.get().isPresent()) { // 저장은 됐고 그 뒤에 오류
                    TravelProduct p = saved.get().get();
                    Messages.flash(redirect, Level.WARNING, "처리 중 오류가 있었지만 '" + p.getProductName()
                            + "' 상품은 등록되었습니다. 내용을 확인해 주세요.");
                    return "redirect:/travel/staff/products/" + p.getProductId() + "/";
                }
                Messages.now(model, Level.ERROR, saved.isPresent()
                        ? "일시적인 오류로 상품이 등록되지 않았습니다. 입력한 내용 그대로 다시 등록해 주세요."
                        : "일시적인 오류로 등록 여부를 확인하지 못했습니다. 상품 관리 목록에서 확인한 뒤 없으면 다시 등록해 주세요.");
            }
        }
        model.addAttribute("errors", errors);
        model.addAttribute("minDate", LocalDate.now(clock).plusDays(1));
        return "travel/staff/product_register";
    }

    /** DB 오류 뒤 등록 여부. 바깥 empty = 확인 불가, 안쪽 empty = 저장 안 됨. */
    private Optional<Optional<TravelProduct>> findAfterError(ProductForm form, OffsetDateTime since) {
        try {
            return Optional.of(travel.findRegisteredSince(form.input(), since));
        } catch (DataAccessException e) {
            log.error("상품 등록 여부 확인 실패", e);
            return Optional.empty();
        }
    }

    /** LoadDefaultConfiguration (JSON) */
    @GetMapping("/default-configuration/")
    @ResponseBody
    ResponseEntity<Map<String, Object>> loadDefaultConfiguration(@RequestParam(required = false) String theme,
                                                             @RequestParam(name = "tour_level", required = false) String level) {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "configuration",
                    travel.loadDefaultConfiguration(Theme.parse(theme), TourLevel.parse(level))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    /** 조회 실패는 '상품 없음'과 구분 (UC7 2a). */
    private void manageContext(Model model, String theme, String level, HttpServletRequest request) {
        Theme t = Theme.parse(theme);
        TourLevel lv = TourLevel.parse(level);
        List<TravelProduct> products = List.of();
        boolean loadFailed = false;
        try {
            products = travel.loadProducts(t, lv);
        } catch (DataAccessException e) {
            log.error("직원 상품 목록 조회 실패 theme={} level={}", t, lv, e);
            loadFailed = true;
        }
        model.addAttribute("products", products);
        model.addAttribute("loadFailed", loadFailed);
        model.addAttribute("filterTheme", t);
        model.addAttribute("filterLevel", lv);
        model.addAttribute("query", request.getQueryString() == null ? "" : "?" + request.getQueryString());
    }

    /** ManageTourProduct */
    @GetMapping("/products/")
    String manageTourProduct(@RequestParam(required = false) String theme,
                  @RequestParam(name = "tour_level", required = false) String level,
                  HttpServletRequest request, Model model) {
        manageContext(model, theme, level, request);
        return "travel/staff/product_manage";
    }

    /** SelectProduct */
    @GetMapping("/products/{productId}/")
    String selectProduct(@PathVariable long productId, @RequestParam(required = false) String theme,
                        @RequestParam(name = "tour_level", required = false) String level,
                        HttpServletRequest request, Model model, RedirectAttributes redirect) {
        TravelProduct selected;
        try {
            selected = travel.selectProduct(productId);
        } catch (ProductNotFoundException e) {
            Messages.flash(redirect, Level.ERROR, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/travel/staff/products/";
        }
        return detailView(selected, ProductForm.of(selected), Map.of(), theme, level, request, model);
    }

    private String detailView(TravelProduct selected, ProductForm form, Map<String, String> errors, String theme,
                              String level, HttpServletRequest request, Model model) {
        manageContext(model, theme, level, request);
        model.addAttribute("selected", selected);
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("hasApplications", travel.hasApplications(selected.getProductId()));
        model.addAttribute("minDate", LocalDate.now(clock).plusDays(1));
        return "travel/staff/product_manage";
    }

    @PostMapping("/products/{productId}/")
    String modifyTourProduct(@PathVariable long productId, @ModelAttribute("form") ProductForm form,
                  @RequestParam(required = false) MultipartFile image,
                  @RequestParam(name = "image-clear", required = false) String imageClear,
                  HttpServletRequest request, Model model, RedirectAttributes redirect) {
        TravelProduct selected;
        try {
            selected = travel.selectProduct(productId);
        } catch (ProductNotFoundException e) {
            Messages.flash(redirect, Level.ERROR, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
            return "redirect:/travel/staff/products/";
        }
        Map<String, String> errors = new LinkedHashMap<>(form.validate());
        if (errors.isEmpty()) {
            try {
                TravelProduct p = travel.modifyTourProduct(productId, form.input(), imageChange(image, imageClear));
                Messages.flash(redirect, Level.SUCCESS, "'" + p.getProductName() + "' 상품을 수정했습니다.");
                return "redirect:/travel/staff/products/" + productId + "/";
            } catch (InvalidImageException e) {
                errors.put("image", e.getMessage());
            } catch (ProductValidationException e) {
                errors.putAll(e.getErrors());
            } catch (ProductHasApplicationsException e) {
                Messages.now(model, Level.ERROR, "신청이 있는 상품은 수정할 수 없습니다. 기존 정보를 유지했습니다.");
            } catch (ProductNotFoundException e) {
                Messages.flash(redirect, Level.ERROR, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
                return "redirect:/travel/staff/products/";
            } catch (DataAccessException e) { // 한 트랜잭션이라 보통 반영 안 됨. 저장된 값을 다시 보여 준다
                log.error("상품 수정 중 DB 오류 product={}", productId, e);
                Messages.flash(redirect, Level.ERROR, "일시적인 오류로 수정이 반영되지 않았을 수 있습니다. "
                        + "아래에 다시 불러온 저장 내용을 확인한 뒤 필요하면 다시 수정해 주세요.");
                return "redirect:/travel/staff/products/" + productId + "/";
            }
        }
        return detailView(selected, form, errors, null, null, request, model);
    }

    @PostMapping("/products/{productId}/delete/")
    String deleteTourProduct(@PathVariable long productId, RedirectAttributes redirect) {
        try {
            String name = travel.deleteTourProduct(productId);
            Messages.flash(redirect, Level.SUCCESS, "'" + name + "' 상품을 삭제했습니다.");
        } catch (ProductHasApplicationsException e) {
            Messages.flash(redirect, Level.ERROR, "신청이 있는 상품은 삭제할 수 없습니다.");
            return "redirect:/travel/staff/products/" + productId + "/";
        } catch (ProductNotFoundException e) {
            Messages.flash(redirect, Level.ERROR, "상품을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
        } catch (DataAccessException e) {
            log.error("상품 삭제 중 DB 오류 product={}", productId, e);
            Messages.flash(redirect, Level.ERROR, "일시적인 오류로 삭제 여부를 확인하지 못했습니다. 목록에서 상품이 남아 있는지 확인해 주세요.");
        }
        return "redirect:/travel/staff/products/";
    }
}
