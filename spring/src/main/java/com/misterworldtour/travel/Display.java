package com.misterworldtour.travel;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 화면 표시용 정보: 테마 소개 문구와 사진 경로. 업무 규칙과는 무관하다 (Django 판 travel/display.py). */
public final class Display {

    /** 테마 하나의 화면 정보. code·label 은 메뉴·검색, 나머지는 테마 카드. */
    public record ThemeView(String code, String label, String shortLabel, String en, String icon,
                            String tagline, String photoUrl, String photoSm) {
    }

    /** 등급 하나의 화면 정보. summary 예: "4등급 호텔 · 현지식 레스토랑 식사". */
    public record LevelView(String code, String label, String icon, String summary) {
    }

    private record ThemeInfo(String en, String icon, String tagline, String photo) {
    }

    // 테마별 소개 (과제 안내의 테마 설명을 바탕으로 한 화면 문구)
    private static final Map<Theme, ThemeInfo> THEME_INFO = Map.of(
            Theme.HONEYMOON, new ThemeInfo("Honeymoon Romance", "bi-heart",
                    "둘만을 위한 로맨틱 스페셜 룸과 2인 전용 고급차량", "theme_honeymoon"),
            Theme.FILIAL, new ThemeInfo("Parents Healing", "bi-flower1",
                    "고품격 안마·지압 서비스와 건강 인삼 선물", "theme_filial"),
            Theme.GOLF, new ThemeInfo("Golf Challenge", "bi-flag",
                    "명문 골프 리조트 라운드와 골프공 기념품", "theme_golf"),
            Theme.TREKKING, new ThemeInfo("Outdoor Trekking", "bi-tree",
                    "산과 길이 주는 모험, 아웃도어 스카프와 함께", "theme_trekking"));

    // 투어 등급 아이콘 (홈 검색 선택 패널)
    private static final Map<TourLevel, String> LEVEL_ICONS = Map.of(
            TourLevel.CLASSIC, "bi-star", TourLevel.GRAND, "bi-stars", TourLevel.PREMIUM, "bi-gem");

    private static final List<ThemeView> THEMES = Arrays.stream(Theme.values())
            .map(t -> {
                ThemeInfo i = THEME_INFO.get(t);
                return new ThemeView(t.name(), t.getLabel(), t.getShortLabel(), i.en(), i.icon(), i.tagline(),
                        photoUrl(i.photo(), false), photoUrl(i.photo(), true));
            })
            .toList();

    private static final List<LevelView> LEVELS = Arrays.stream(TourLevel.values())
            .map(lv -> new LevelView(lv.name(), lv.getLabel(), LEVEL_ICONS.get(lv),
                    lv.getDefaultHotel().getLabel() + " · " + lv.getDefaultMeal().getLabel()))
            .toList();

    private Display() {
    }

    public static String photoUrl(String name, boolean small) {
        return "/img/photos/" + (small ? "sm/" : "") + name + ".jpg";
    }

    public static List<ThemeView> themes() {
        return THEMES;
    }

    public static List<LevelView> levels() {
        return LEVELS;
    }
}
