package com.misterworldtour.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** 홈(설계 UI MainView)과 사진 출처. */
@Controller
class HomeController {

    private final JsonMapper jsonMapper;
    private volatile List<Map<String, String>> credits;

    HomeController(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 역할별 홈. 고객·비로그인은 테마 검색, 직원은 직원 메뉴.
     * TODO(travel·souvenir 이식 후): 곧 출발하는 여행 8개, 진행 중인 신청 수, 직원 통계·재고 부족 기념품.
     */
    @GetMapping("/")
    String home() {
        return "home";
    }

    /** 사진 출처 (CC BY·BY-SA 라이선스의 저작자 표시 의무). */
    @GetMapping("/credits/")
    String credits(Model model) {
        model.addAttribute("credits", loadCredits());
        return "credits";
    }

    private List<Map<String, String>> loadCredits() {
        List<Map<String, String>> c = credits;
        if (c == null) {
            try (InputStream in = new ClassPathResource("static/img/photos/credits.json").getInputStream()) {
                c = jsonMapper.readValue(in, new TypeReference<>() { });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            credits = c;
        }
        return c;
    }
}
