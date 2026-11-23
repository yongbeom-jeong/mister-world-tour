package com.misterworldtour.speech;

import com.misterworldtour.speech.VoiceDialogClient.Action;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙으로 하는 음성 예약 대화. 최종발표 시나리오(추천 → 기념일 → 테마 → 스타일 → 음료 → 확인)를 그대로 따른다.
 * 기본값이고, 테스트와 LLM 호출이 실패한 턴의 대체 응답으로도 쓴다.
 */
public class ScriptedDialogClient implements VoiceDialogClient {

    // '예'는 '예약'과 겹쳐서 넣지 않는다
    static final List<String> YES = List.of("네", "응","좋아", "좋습니다", "좋겠", "좋네", "맞아", "맞습니다", "맞네",
            "그래", "그렇게", "오케이");
    static final List<String> NO = List.of("없어", "없습니다", "없네", "아니", "괜찮아", "괜찮습니다", "됐어", "충분");
    static final List<String> OCCASION = List.of("결혼", "신혼", "웨딩", "허니문", "약혼");
    static final List<String> REMOVE = List.of("빼", "취소", "말고");
    static final Map<String, String> DRINK_WORDS = new LinkedHashMap<>();
    static final Map<String, Integer> COUNT_WORDS = Map.ofEntries(Map.entry("한", 1), Map.entry("두", 2),
            Map.entry("세", 3), Map.entry("네", 4), Map.entry("다섯", 5), Map.entry("여섯", 6), Map.entry("일곱", 7),
            Map.entry("여덟", 8), Map.entry("아홉", 9), Map.entry("열", 10));
    static final Pattern COUNT_NUM = Pattern.compile("(\\d{1,2})\\s*(명|분|사람)");
    static final Pattern COUNT_WORD = Pattern.compile("(한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(명|분|사람)");
    static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");

    static final String P_OCCASION = "OCCASION";
    static final String P_THEME = "THEME";
    static final String P_LEVEL = "LEVEL:";
    static final String P_COUNT = "COUNT";
    static final String P_CONFIRM = "CONFIRM";
    static final String P_MORE = "MORE";

    static {
        DRINK_WORDS.put("커피", "COFFEE");
        DRINK_WORDS.put("샴페인", "CHAMPAGNE");
        DRINK_WORDS.put("와인", "WINE");
        DRINK_WORDS.put("맥주", "BEER");
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
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

    /** "다음달 오늘", "12월 24일". 지난 날짜면 내년. */
    static Optional<LocalDate> parseDate(String raw, LocalDate today) {
        String t = VoiceSelectionInterpreter.norm(raw);
        if (t.contains("다음달오늘") || t.contains("다음달이맘때") || t.contains("한달뒤")) {
            return Optional.of(today.plusMonths(1));
        }
        Matcher m = MONTH_DAY.matcher(raw);
        if (m.find()) {
            try {
                LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return Optional.of(d.isBefore(today) ? d.plusYears(1) : d);
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static Optional<Integer> parseCount(String raw) {
        Matcher m = COUNT_NUM.matcher(raw);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        m = COUNT_WORD.matcher(raw);
        if (m.find()) {
            return Optional.of(COUNT_WORDS.get(m.group(1)));
        }
        if (raw.contains("혼자")) {
            return Optional.of(1);
        }
        return Optional.empty();
    }

    /** 테마(·스타일)에 맞는 상품 중 원하는 출발일에 가장 가까운 것 (없으면 가장 빠른 출발). */
    static Optional<ProductInfo> pick(List<ProductInfo> products, String theme, String level, String wishDate) {
        LocalDate wish = wishDate == null ? null : LocalDate.parse(wishDate);
        Comparator<ProductInfo> order = wish == null
                ? Comparator.comparing(ProductInfo::departureDate)
                : Comparator.comparingLong((ProductInfo p) -> Math.abs(p.departureDate().toEpochDay() - wish.toEpochDay()))
                .thenComparing(ProductInfo::departureDate);
        return products.stream()
                .filter(p -> p.theme().equals(theme) && (level == null || p.tourLevel().equals(level)))
                .min(order);
    }

    /** 말 속의 여행지·상품 고유 단어로 상품 하나를 찾는다. 여럿이면 테마로 좁히고, 그래도 여럿이면 없음. */
    static Optional<ProductInfo> byPlace(List<ProductInfo> products, String text, String theme) {
        List<ProductInfo> hits = products.stream().filter(p -> placeWords(p).stream().anyMatch(text::contains)).toList();
        if (hits.size() > 1 && theme != null) {
            hits = hits.stream().filter(p -> p.theme().equals(theme)).toList();
        }
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /** 상품 고유 단어 + 여행지 낱말 ('괌'처럼 한 글자 여행지도 포함). */
    static java.util.Set<String> placeWords(ProductInfo p) {
        java.util.Set<String> words = new java.util.LinkedHashSet<>(VoiceSelectionInterpreter.distinctWords(p.name(), p.destination()));
        for (String w : (p.destination() == null ? "" : p.destination()).split("[\\s·,()/]+")) {
            String n = VoiceSelectionInterpreter.norm(w);
            if (!n.isEmpty() && !VoiceSelectionInterpreter.GENERIC_WORDS.contains(n)) {
                words.add(n);
            }
        }
        return words;
    }

    /** "괌(12월 24일, 프리미엄), 발리(12월 3일, 그랜드) 상품이 있어요." 원하는 출발일에 가까운 순, 네 곳까지. */
    static String places(List<ProductInfo> products, String theme, String wishDate) {
        LocalDate wish = wishDate == null ? null : LocalDate.parse(wishDate);
        Comparator<ProductInfo> order = wish == null
                ? Comparator.comparing(ProductInfo::departureDate)
                : Comparator.comparingLong((ProductInfo p) -> Math.abs(p.departureDate().toEpochDay() - wish.toEpochDay()));
        List<String> out = products.stream().filter(p -> p.theme().equals(theme)).sorted(order).limit(4)
                .map(p -> p.destination() + "(" + VoiceDialogText.date(p.departureDate()) + " 출발, " + p.levelLabel() + ")")
                .toList();
        return String.join(", ", out) + " 상품이 있어요.";
    }

    private static String themeLabel(String code) {
        Theme t = Theme.parse(code);
        return t == null ? code : t.getLabel();
    }

    private static String levelLabel(String code) {
        TourLevel l = TourLevel.parse(code);
        return l == null ? code : l.getLabel();
    }

    @Override
    public DialogReply reply(DialogRequest req) {
        String raw = req.utterance() == null ? "" : req.utterance();
        String t = VoiceSelectionInterpreter.norm(raw);
        DialogSlots before = req.slots();
        String pending = before.pending() == null ? "" : before.pending();
        boolean no = containsAny(t, NO);
        boolean yes = !no && containsAny(t, YES);

        String theme = before.theme();
        String level = before.tourLevel();
        Long productId = before.productId();
        String wish = before.departureDate();
        Integer count = before.travelerCount();
        List<String> drinks = new ArrayList<>(before.drinks());

        Set<Theme> themes = find(VoiceSelectionInterpreter.THEME_ALIASES, t);
        if (themes.size() == 1) {
            String said = themes.iterator().next().name();
            if (!said.equals(theme)) {
                theme = said;
                level = null;
                productId = null;
                count = null;
            }
        }
        Set<TourLevel> levels = find(VoiceSelectionInterpreter.LEVEL_ALIASES, t);
        if (levels.size() == 1) {
            String said = levels.iterator().next().name();
            if (!said.equals(level)) {
                level = said;
                productId = null;
            }
        } else if (levels.isEmpty() && yes && pending.startsWith(P_LEVEL)) {
            level = pending.substring(P_LEVEL.length());
        }
        Optional<LocalDate> date = parseDate(raw, req.today());
        if (date.isPresent() && !date.get().toString().equals(wish)) {
            wish = date.get().toString();
            productId = null;
        }
        boolean remove = containsAny(t, REMOVE);
        for (Map.Entry<String, String> d : DRINK_WORDS.entrySet()) {
            if (t.contains(d.getKey())) {
                drinks.remove(d.getValue());
                if (!remove) {
                    drinks.add(d.getValue());
                }
            }
        }
        Optional<Integer> said = parseCount(raw);
        if (said.isPresent()) {
            count = said.get();
        }

        ProductInfo product = productId == null ? null
                : req.products().stream().filter(p -> p.productId() == before.productId()).findFirst().orElse(null);
        // 여행지·상품 이름을 말하면 그 상품으로 ("몰디브 가고 싶어요")
        Optional<ProductInfo> named = byPlace(req.products(), t, theme);
        if (named.isPresent() && (product == null || named.get().productId() != product.productId())) {
            product = named.get();
            if (!product.theme().equals(theme)) {
                count = said.orElse(null);
            }
            theme = product.theme();
            level = product.tourLevel();
        }
        if (product == null && theme != null && level != null) {
            product = pick(req.products(), theme, level, wish).orElse(null);
        }
        productId = product == null ? null : product.productId();
        if (product != null && product.remainingSeats() < 0) {
            count = 2; // 허니문은 2인
        }
        DialogSlots now = new DialogSlots(theme, level, productId, wish, count, before.hotelLevel(), before.meal(),
                drinks, null);

        if (theme == null) {
            if (containsAny(t, OCCASION)) {
                return ask(now, "정말 축하드려요. " + Theme.HONEYMOON.getLabel() + " 또는 " + Theme.TREKKING.getLabel()
                        + "는 어떠세요?", P_THEME);
            }
            if (t.contains("추천")) {
                return ask(now, "무슨 기념일인가요?", P_OCCASION);
            }
            return ask(now, "허니문 낭만 투어, 효도 힐링 투어, 골프 챌린지 투어, 아웃도어 트레킹 투어 중에서 말씀해 주세요.", P_THEME);
        }
        if (level == null) {
            Optional<ProductInfo> nearest = pick(req.products(), theme, null, wish);
            if (nearest.isEmpty()) {
                DialogSlots reset = new DialogSlots(null, null, null, wish, null, null, null, drinks, null);
                return ask(reset, "죄송해요, 지금 신청할 수 있는 " + themeLabel(theme) + " 상품이 없어요. 다른 테마는 어떠세요?", P_THEME);
            }
            String suggest = nearest.get().tourLevel();
            return ask(now, themeLabel(theme) + " 알겠습니다. " + places(req.products(), theme, wish)
                    + " 그리고 스타일은 " + levelLabel(suggest) + " 스타일 어떨까요?", P_LEVEL + suggest);
        }
        if (product == null) {
            DialogSlots noLevel = new DialogSlots(theme, null, null, wish, count, null, null, drinks, null);
            return ask(noLevel, "죄송해요, " + themeLabel(theme) + " " + levelLabel(level)
                    + " 스타일 상품은 지금 없어요. 다른 스타일은 어떠세요?", null);
        }
        if (count == null) {
            return ask(now, product.name() + ", " + VoiceDialogText.date(product.departureDate())
                    + " 출발이에요. 몇 분이 가시나요?", P_COUNT);
        }
        if (!now.sameChoice(before)) {
            return ask(now, VoiceDialogText.selection(req.customerName(), product, now), P_CONFIRM);
        }
        if (no && (pending.equals(P_CONFIRM) || pending.equals(P_MORE))) {
            return new DialogReply("", now, Action.BOOK);
        }
        if (yes && pending.equals(P_CONFIRM)) {
            return ask(now, "추가로 필요하신 것 있으세요?", P_MORE);
        }
        if (yes && pending.equals(P_MORE)) {
            return ask(now, "어떤 걸 추가해 드릴까요? 음료는 커피, 샴페인, 와인, 수제맥주가 있어요.", P_MORE);
        }
        return ask(now, "다시 한 번 말씀해 주시겠어요? 이대로 좋으시면 '맞아요', 더 필요한 게 없으면 '없어요'라고 해 주세요.",
                pending.isEmpty() ? P_CONFIRM : pending);
    }

    private static DialogReply ask(DialogSlots slots, String reply, String pending) {
        return new DialogReply(reply, slots.withPending(pending), Action.ASK);
    }
}
