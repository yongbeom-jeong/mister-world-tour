package com.misterworldtour.travel;

import com.misterworldtour.common.Messages;
import com.misterworldtour.common.Messages.Level;
import com.misterworldtour.members.CustomerRequired;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 고객 화면: 상품 목록·상세 (UC4, 설계 UI 5장 TravelProductView). */
@Controller
@RequestMapping("/travel")
@CustomerRequired
class CustomerTravelController {

    private static final Logger log = LoggerFactory.getLogger(CustomerTravelController.class);

    private final BookingService booking;

    CustomerTravelController(BookingService booking) {
        this.booking = booking;
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

    // ---------------------------------------------------------------- UC4 상품 선택

    @GetMapping("/products/{productId}/")
    String productDetail(@PathVariable long productId, Model model, RedirectAttributes redirect) {
        TravelProduct product;
        try {
            product = booking.getBookableProduct(productId);
        } catch (BookingException e) {
            Messages.flash(redirect, Level.ERROR, e.getMessage());
            return "redirect:/travel/products/";
        }
        model.addAttribute("product", product);
        model.addAttribute("remaining", booking.remainingSeats(product));
        model.addAttribute("bodyClass", "product-detail-page");
        return "travel/customer/product_detail";
    }
}
