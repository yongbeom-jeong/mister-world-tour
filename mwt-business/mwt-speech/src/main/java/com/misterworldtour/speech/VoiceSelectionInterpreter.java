package com.misterworldtour.speech;

import com.misterworldtour.speech.SpeechRecognitionAPI.Kind;
import com.misterworldtour.speech.SpeechRecognitionAPI.ProductCandidate;
import com.misterworldtour.speech.SpeechRecognitionAPI.Status;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionContext;
import com.misterworldtour.speech.SpeechRecognitionAPI.VoiceSelectionResult;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 인식된 문장을 테마·투어 등급 조회 조건이나 상품 선택으로 해석한다 (설계 8.1 VoiceSelectionInterpreter).
 * 해석 결과는 선택 값만 돌려준다. 상품 검증·등급 제한·가격은 Travel Management 가 화면 선택과 같은 경로로 처리한다.
 */
@Component
public class VoiceSelectionInterpreter {

    // 별칭은 임시 정책 (설계상 명령 문형·별칭은 연동 시험에서 구체화)
    static final Map<Theme, List<String>> THEME_ALIASES = new LinkedHashMap<>();
    static final Map<TourLevel, List<String>> LEVEL_ALIASES = new LinkedHashMap<>();
    static final Map<String, Integer> ORDINALS = Map.ofEntries(Map.entry("첫", 1), Map.entry("한", 1),
            Map.entry("두", 2), Map.entry("세", 3), Map.entry("네", 4), Map.entry("다섯", 5), Map.entry("여섯", 6),
            Map.entry("일곱", 7), Map.entry("여덟", 8), Map.entry("아홉", 9), Map.entry("열", 10));
    /** 상품 이름에서 고유 단어를 고를 때 제외할 흔한 단어. */
    static final Set<String> GENERIC_WORDS = Set.of("투어", "여행", "챌린지", "힐링", "로맨틱", "트레킹", "골프",
            "허니문", "효도", "상품", "패키지");
    static final Pattern ORDINAL_RE = Pattern.compile(
            "(\\d{1,2})\\s*번|(첫|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*번\\s*째");
    private static final Pattern NORM = Pattern.compile("[\\s.,!?·~\\-]+");
    private static final Pattern SPLIT = Pattern.compile("[\\s·,()/]+");

    static {
        THEME_ALIASES.put(Theme.HONEYMOON, List.of("허니문", "신혼", "신혼여행", "웨딩"));
        THEME_ALIASES.put(Theme.FILIAL, List.of("효도", "부모님", "효도여행", "효도힐링"));
        THEME_ALIASES.put(Theme.GOLF, List.of("골프"));
        THEME_ALIASES.put(Theme.TREKKING, List.of("트레킹", "트래킹", "등산", "아웃도어", "산행"));
        LEVEL_ALIASES.put(TourLevel.CLASSIC, List.of("클래식", "기본형", "실속"));
        LEVEL_ALIASES.put(TourLevel.GRAND, List.of("그랜드", "그랜", "중급"));
        LEVEL_ALIASES.put(TourLevel.PREMIUM, List.of("프리미엄", "프리미움", "고급형", "최고급"));
    }

    static String norm(String text) {
        return NORM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static <K> Set<K> find(Map<K, List<String>> aliases, String text) {
        Set<K> found = new LinkedHashSet<>();
        aliases.forEach((key, words) -> {
            if (words.stream().anyMatch(text::contains)) {
                found.add(key);
            }
        });
        return found;
    }

    static Set<String> distinctWords(String name, String destination) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : SPLIT.split(name + " " + (destination == null ? "" : destination))) {
            String w = norm(raw);
            if (w.length() >= 2 && !GENERIC_WORDS.contains(w) && !w.contains("투어") && !w.contains("여행")) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * 인식된 문장과 현재 화면의 테마·투어 등급·상품 목록으로 선택 의도를 해석한다.
     * 상품 조회 요청이면 테마·등급을, 상품 선택 요청이면 현재 목록의 상품 번호를 돌려준다.
     * 하나로 정할 수 없거나 지원하지 않는 명령이면 그 사유를 돌려준다 (적용할 값 없음 → 직전 선택 유지).
     */
    public VoiceSelectionResult interpretSelection(String recognizedText, VoiceSelectionContext context) {
        String text = norm(recognizedText);

        // 1) 상품 선택: 순서("두 번째", "2번") 또는 상품 고유 단어(여행지·이름)
        Matcher m = ORDINAL_RE.matcher(recognizedText);
        if (m.find() && !context.products().isEmpty()) {
            int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : ORDINALS.get(m.group(2));
            if (n >= 1 && n <= context.products().size()) {
                ProductCandidate p = context.products().get(n - 1);
                return new VoiceSelectionResult(Status.SUCCESS, "'" + p.name() + "' 상품을 선택합니다.", Kind.PRODUCT, null, null,
                        p.productId(), "");
            }
            return VoiceSelectionResult.of(Status.UNSUPPORTED, n + "번째 상품이 목록에 없습니다. 지금 목록에는 "
                    + context.products().size() + "개가 있습니다.");
        }

        List<ProductCandidate> matches = new ArrayList<>();
        for (ProductCandidate p : context.products()) {
            if (text.contains(norm(p.name())) || distinctWords(p.name(), p.destination()).stream().anyMatch(text::contains)) {
                matches.add(p);
            }
        }
        if (matches.size() == 1) {
            ProductCandidate p = matches.get(0);
            return new VoiceSelectionResult(Status.SUCCESS, "'" + p.name() + "' 상품을 선택합니다.", Kind.PRODUCT, null, null,
                    p.productId(), "");
        }
        if (matches.size() > 1) {
            String names = matches.stream().limit(3).map(ProductCandidate::name).collect(Collectors.joining(", "));
            return VoiceSelectionResult.of(Status.AMBIGUOUS,
                    "여러 상품이 해당됩니다 (" + names + "). 상품 이름이나 순서(예: 두 번째)로 다시 말해 주세요.");
        }

        // 2) 테마·등급 조회 조건: 말한 항목만 돌려주고 나머지는 화면 상태 유지
        Set<Theme> themes = find(THEME_ALIASES, text);
        Set<TourLevel> levels = find(LEVEL_ALIASES, text);
        if (themes.size() > 1 || levels.size() > 1) {
            return VoiceSelectionResult.of(Status.AMBIGUOUS, "테마나 등급을 하나만 말해 주세요. 예: '골프 그랜드'");
        }
        if (!themes.isEmpty() || !levels.isEmpty()) {
            Theme theme = themes.isEmpty() ? null : themes.iterator().next();
            TourLevel level = levels.isEmpty() ? null : levels.iterator().next();
            List<String> said = new ArrayList<>();
            if (theme != null) {
                said.add(theme.getLabel());
            }
            if (level != null) {
                said.add(level.getLabel());
            }
            return new VoiceSelectionResult(Status.SUCCESS, String.join(" ", said) + " 상품을 찾습니다.", Kind.FILTER, theme, level,
                    null, "");
        }
        return VoiceSelectionResult.of(Status.UNSUPPORTED, "테마·등급이나 상품을 말해 주세요. 예: '골프 그랜드', '몰디브', '두 번째 상품'");
    }
}
