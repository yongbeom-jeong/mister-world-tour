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
    static final String P_PRODUCT = "PRODUCT";
    static final String P_CONFIG = "CONFIG";
    static final String P_FINAL = "FINAL";
    static final Pattern HOTEL_STAR = Pattern.compile("([345])\s*(성|등급)");
    static final String CONFIG_QUESTION = "호텔·식사를 바꾸거나 음료를 추가하시겠어요?";
    static final String CONFIG_HELP = "어떤 걸 바꿔 드릴까요? 호텔 등급, 식사(현지식·고급 레스토랑), 음료(커피·샴페인·와인·수제맥주)를 바꿀 수 있어요.";
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

    /** 테마·등급의 상품, 원하는 출발일에 가까운 순 (없으면 출발일 순). */
    static List<ProductInfo> candidates(List<ProductInfo> products, String theme, String level, String wishDate) {
        LocalDate wish = wishDate == null ? null : LocalDate.parse(wishDate);
        Comparator<ProductInfo> order = wish == null
                ? Comparator.comparing(ProductInfo::departureDate)
                : Comparator.comparingLong((ProductInfo p) -> Math.abs(p.departureDate().toEpochDay() - wish.toEpochDay()))
                .thenComparing(ProductInfo::departureDate);
        return products.stream().filter(p -> p.theme().equals(theme) && p.tourLevel().equals(level)).sorted(order)
                .limit(4).toList();
    }

    /** "미국령 괌(12월 24일 출발), 몰디브 말레(1월 7일 출발)". */
    static String places(List<ProductInfo> list) {
        return String.join(", ", list.stream()
                .map(p -> p.destination() + "(" + VoiceDialogText.date(p.departureDate()) + " 출발)").toList());
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
            Matcher dm = Pattern.compile(d.getKey() + "\\s*(\\d{1,2}|한|두|세|네|다섯)?\\s*(포트|병|세트|잔|개|캔)?").matcher(raw);
            if (dm.find()) { // 말한 음료는 말한 수량으로 (수량을 안 말하면 1)
                drinks.removeIf(d.getValue()::equals);
                if (!remove) {
                    String q = dm.group(1);
                    int n = q == null ? 1 : q.chars().allMatch(Character::isDigit) ? Integer.parseInt(q) : COUNT_WORDS.get(q);
                    for (int i = 0; i < n; i++) {
                        drinks.add(d.getValue());
                    }
                }
            }
        }
        Optional<Integer> said = parseCount(raw);
        if (said.isPresent()) {
            count = said.get();
        }
        String hotel = before.hotelLevel();
        String meal = before.meal();
        Matcher hm = HOTEL_STAR.matcher(raw);
        if (hm.find()) {
            hotel = "STAR" + hm.group(1);
        }
        if (t.contains("도시락")) {
            meal = "LUNCHBOX";
        } else if (t.contains("현지식")) {
            meal = "LOCAL";
        } else if (t.contains("고급레스토랑") || t.contains("스테이크")) {
            meal = "FINE";
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
        // 상품 선택 (화면처럼 테마·등급의 상품 중에서): 날짜가 딱 맞거나 하나뿐이면 바로, 아니면 여행지를 들려주고 고르게 한다
        List<ProductInfo> choices = List.of();
        boolean picked = named.isPresent() && product == named.get();
        if (product == null && theme != null && level != null) {
            choices = candidates(req.products(), theme, level, wish);
            if (pending.equals(P_PRODUCT)) {
                Matcher om = VoiceSelectionInterpreter.ORDINAL_RE.matcher(raw);
                if (om.find()) {
                    int n = om.group(1) != null ? Integer.parseInt(om.group(1)) : VoiceSelectionInterpreter.ORDINALS.get(om.group(2));
                    product = n >= 1 && n <= choices.size() ? choices.get(n - 1) : null;
                }
            }
            if (product == null && wish != null) {
                String w = wish;
                product = choices.stream().filter(p -> p.departureDate().toString().equals(w)).findFirst().orElse(null);
            }
            if (product == null && choices.size() == 1) {
                product = choices.get(0);
            }
            picked = product != null;
        }
        ProductInfo chosen = product;
        List<ProductInfo> others = picked
                ? candidates(req.products(), chosen.theme(), chosen.tourLevel(), wish).stream()
                        .filter(p -> p.productId() != chosen.productId()).toList()
                : List.of();
        productId = product == null ? null : product.productId();
        if (product != null && product.remainingSeats() < 0) {
            count = 2; // 허니문은 2인
        }
        DialogSlots now = new DialogSlots(theme, level, productId, wish, count, hotel, meal, drinks, null);

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
            return ask(now, themeLabel(theme) + " 알겠습니다. 그리고 스타일은 " + levelLabel(suggest) + " 스타일 어떨까요?",
                    P_LEVEL + suggest);
        }
        if (product == null && !choices.isEmpty()) {
            return ask(now, levelLabel(level) + " 스타일 " + themeLabel(theme) + "는 " + places(choices)
                    + " 상품이 있어요. 어디로 가실래요?", P_PRODUCT);
        }
        if (product == null) {
            DialogSlots noLevel = new DialogSlots(theme, null, null, wish, count, null, null, drinks, null);
            return ask(noLevel, "죄송해요, " + themeLabel(theme) + " " + levelLabel(level)
                    + " 스타일 상품은 지금 없어요. 다른 스타일은 어떠세요?", null);
        }
        String alternatives = others.isEmpty() ? "" : " 다른 곳을 원하시면 " + places(others) + "도 있어요.";
        if (count == null) {
            return ask(now, product.name() + ", " + VoiceDialogText.date(product.departureDate()) + " 출발이에요."
                    + alternatives + " 몇 분이 가시나요?", P_COUNT);
        }
        if (!now.sameChoice(before)) {
            String summary = VoiceDialogText.selection(req.customerName(), product, now);
            if (picked || pending.equals(P_COUNT)) { // 상품을 막 정했으면 다음은 구성 변경
                return ask(now, summary + alternatives + " " + CONFIG_QUESTION, P_CONFIG);
            }
            return ask(now, summary, P_CONFIRM);
        }
        if (pending.equals(P_CONFIG)) {
            return no ? ask(now, "알겠습니다. 이대로 예약할까요?", P_FINAL) : ask(now, CONFIG_HELP, P_CONFIG);
        }
        if (pending.equals(P_FINAL)) {
            return yes ? new DialogReply("", now, Action.BOOK) : ask(now, CONFIG_HELP, P_CONFIG);
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
