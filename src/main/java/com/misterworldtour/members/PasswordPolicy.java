package com.misterworldtour.members;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 비밀번호 규칙. Django 판의 AUTH_PASSWORD_VALIDATORS 4개를 같은 방식으로 옮겼다.
 * <ul>
 *   <li>아이디·성명과 비슷함 (UserAttributeSimilarityValidator, 유사도 0.7)</li>
 *   <li>8자 이상 (MinimumLengthValidator)</li>
 *   <li>흔한 비밀번호 금지 (CommonPasswordValidator, Django 와 같은 목록 약 2만 개)</li>
 *   <li>숫자로만 된 비밀번호 금지 (NumericPasswordValidator)</li>
 * </ul>
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final double MAX_SIMILARITY = 0.7;
    private static final Pattern NON_WORD = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

    private final Set<String> commonPasswords;

    public PasswordPolicy() {
        this.commonPasswords = loadCommonPasswords();
    }

    /** 규칙 위반 안내 문구 목록. 비어 있으면 통과. */
    public List<String> validate(String password, String username, String name) {
        List<String> errors = new ArrayList<>();
        if (tooSimilar(password, username) || tooSimilar(password, name)) {
            errors.add("비밀번호가 아이디·성명과 너무 비슷합니다.");
        }
        if (password.length() < MIN_LENGTH) {
            errors.add("비밀번호가 너무 짧습니다. 최소 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        if (commonPasswords.contains(password.toLowerCase(Locale.ROOT).strip())) {
            errors.add("너무 흔한 비밀번호입니다.");
        }
        if (password.chars().allMatch(Character::isDigit)) {
            errors.add("숫자로만 된 비밀번호는 쓸 수 없습니다.");
        }
        return errors;
    }

    private static boolean tooSimilar(String password, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String pw = password.toLowerCase(Locale.ROOT);
        String valueLower = value.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>(List.of(NON_WORD.split(valueLower, -1)));
        parts.add(valueLower);
        for (String part : parts) {
            if (exceedsMaximumLengthRatio(pw, part)) {
                continue;
            }
            if (quickRatio(pw, part) >= MAX_SIMILARITY) {
                return true;
            }
        }
        return false;
    }

    /** 비밀번호가 비교 값보다 훨씬 길면 유사도 검사를 건너뛴다 (Django 와 같은 기준). */
    private static boolean exceedsMaximumLengthRatio(String password, String value) {
        int pwdLen = password.length();
        double lengthBoundSimilarity = MAX_SIMILARITY / 2 * pwdLen;
        int valueLen = value.length();
        return pwdLen >= 10 * valueLen && valueLen < lengthBoundSimilarity;
    }

    /** Python difflib.SequenceMatcher.quick_ratio: 두 문자열에 공통으로 든 글자 수 기준 유사도. */
    static double quickRatio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) {
            return 1.0;
        }
        Map<Integer, Integer> avail = new HashMap<>();
        b.codePoints().forEach(c -> avail.merge(c, 1, Integer::sum));
        int matches = 0;
        for (int c : a.codePoints().toArray()) {
            Integer n = avail.get(c);
            if (n != null && n > 0) {
                avail.put(c, n - 1);
                matches++;
            }
        }
        return 2.0 * matches / total;
    }

    private static Set<String> loadCommonPasswords() {
        Set<String> set = new HashSet<>();
        try (InputStream raw = PasswordPolicy.class.getResourceAsStream("/members/common-passwords.txt.gz")) {
            if (raw == null) {
                throw new IllegalStateException("members/common-passwords.txt.gz 가 없습니다");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                set.add(line.strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return set;
    }
}
