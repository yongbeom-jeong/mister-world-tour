package com.misterworldtour.speech;

import com.misterworldtour.speech.SpeechClient.Recognized;
import com.misterworldtour.speech.SpeechClient.VoiceInput;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * Speech Recognition Integration (설계 08장 SpeechRecognitionManager · VoiceSelectionInterpreter, Django 판 speech/services.py).
 *
 * <ul>
 *   <li>지원 범위: 테마·투어 등급 조회 조건 지정, 현재 화면 목록에서 상품 선택. (옵션 변경·신청은 GUI)</li>
 *   <li>해석 결과는 선택 값만 돌려준다. 상품 검증·등급 제한·가격은 Travel Management 가 GUI 와 같은 경로로 처리한다.</li>
 *   <li>모호하거나 지원하지 않는 말은 적용할 값 없이 안내만 한다 (직전 선택 유지).</li>
 * </ul>
 */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);

    public enum Status { SUCCESS, RECOGNITION_FAILED, AMBIGUOUS, UNSUPPORTED }

    public enum Kind {
        FILTER,  // 테마·등급 조회 조건
        PRODUCT  // 상품 선택
    }

    /** 화면에 보이는 상품 하나 (순서대로). */
    public record ProductCandidate(long productId, String name, String destination) {
    }

    /** 해석 문맥: 현재 화면 조건과 목록. */
    public record Context(Theme theme, TourLevel level, List<ProductCandidate> products) {
    }

    public record Result(Status status, String message, Kind kind, Theme theme, TourLevel level, Long productId,
                         String recognizedText) {

        static Result of(Status status, String message) {
            return new Result(status, message, null, null, null, null, "");
        }

        Result withText(String text) {
            return new Result(status, message, kind, theme, level, productId, text);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status.name());
            m.put("message", message);
            m.put("kind", kind == null ? null : kind.name());
            m.put("theme", theme == null ? null : theme.name());
            m.put("tour_level", level == null ? null : level.name());
            m.put("product_id", productId);
            m.put("recognized_text", recognizedText);
            return m;
        }
    }

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

    /** Web Speech API 오류 코드 → 기록용 설명. 목록 밖 코드는 'other' 로 묶는다 (로그 오염 방지). */
    static final Map<String, String> BROWSER_ERRORS = Map.of(
            "no-speech", "음성 없음", "not-allowed", "마이크 권한 거부", "audio-capture", "마이크 없음",
            "network", "인식 서비스 연결 실패", "aborted", "인식 중단", "service-not-allowed", "인식 서비스 사용 불가",
            "language-not-supported", "언어 미지원", "other", "기타");

    private final SpeechClient client;

    SpeechService(SpeechClient client) {
        this.client = client;
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

    /** 인식 문장 → 선택 값. */
    public Result interpretSelection(String recognizedText, Context context) {
        String text = norm(recognizedText);

        // 1) 상품 선택: 순서("두 번째", "2번") 또는 상품 고유 단어(여행지·이름)
        Matcher m = ORDINAL_RE.matcher(recognizedText);
        if (m.find() && !context.products().isEmpty()) {
            int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : ORDINALS.get(m.group(2));
            if (n >= 1 && n <= context.products().size()) {
                ProductCandidate p = context.products().get(n - 1);
                return new Result(Status.SUCCESS, "'" + p.name() + "' 상품을 선택합니다.", Kind.PRODUCT, null, null,
                        p.productId(), "");
            }
            return Result.of(Status.UNSUPPORTED, n + "번째 상품이 목록에 없습니다. 지금 목록에는 "
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
            return new Result(Status.SUCCESS, "'" + p.name() + "' 상품을 선택합니다.", Kind.PRODUCT, null, null,
                    p.productId(), "");
        }
        if (matches.size() > 1) {
            String names = matches.stream().limit(3).map(ProductCandidate::name).collect(Collectors.joining(", "));
            return Result.of(Status.AMBIGUOUS,
                    "여러 상품이 해당됩니다 (" + names + "). 상품 이름이나 순서(예: 두 번째)로 다시 말해 주세요.");
        }

        // 2) 테마·등급 조회 조건: 말한 항목만 돌려주고 나머지는 화면 상태 유지
        Set<Theme> themes = find(THEME_ALIASES, text);
        Set<TourLevel> levels = find(LEVEL_ALIASES, text);
        if (themes.size() > 1 || levels.size() > 1) {
            return Result.of(Status.AMBIGUOUS, "테마나 등급을 하나만 말해 주세요. 예: '골프 그랜드'");
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
            return new Result(Status.SUCCESS, String.join(" ", said) + " 상품을 찾습니다.", Kind.FILTER, theme, level,
                    null, "");
        }
        return Result.of(Status.UNSUPPORTED, "테마·등급이나 상품을 말해 주세요. 예: '골프 그랜드', '몰디브', '두 번째 상품'");
    }

    /** SpeechRecognitionAPI: 인식 → 해석. 인식 실패·연동 오류는 예외 대신 결과로 알린다. */
    public Result recognizeSelection(VoiceInput input, Context context) {
        Recognized recognized;
        try {
            recognized = client.recognize(input);
        } catch (RuntimeException e) {
            log.error("음성인식 연동 실패", e);
            recognized = Recognized.fail("음성인식 서비스에 연결하지 못했습니다.");
        }
        if (!recognized.ok()) {
            log.warn("음성 선택 실패 단계=인식 오류={}", recognized.error());
            return Result.of(Status.RECOGNITION_FAILED, recognized.error() + " 다시 말하거나 화면에서 직접 선택해 주세요.");
        }
        Result result = interpretSelection(recognized.text(), context).withText(recognized.text());
        if (result.status() != Status.SUCCESS) {
            log.info("음성 선택 {} text={}", result.status(), recognized.text());
        }
        return result;
    }

    /** 브라우저에서 끝난 음성 인식 실패를 기록한다 (UC4 4a·7a). 서버까지 문장이 오지 않은 실패만 해당. */
    public String recordBrowserFailure(String errorCode, Long memberId) {
        String code = BROWSER_ERRORS.containsKey(errorCode) ? errorCode : "other";
        log.warn("음성 선택 실패 단계=브라우저 인식 오류={}({}) member={}", code, BROWSER_ERRORS.get(code), memberId);
        return code;
    }
}
