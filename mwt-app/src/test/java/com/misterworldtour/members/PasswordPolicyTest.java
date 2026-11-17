package com.misterworldtour.members;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void goodPasswordPasses() {
        assertThat(policy.validate("Travel-2026!x", "hong_1", "홍길동")).isEmpty();
    }

    @Test
    void shortNumericAndCommonAreRejected() {
        assertThat(policy.validate("1234", "hong_1", "홍길동"))
                .anyMatch(m -> m.contains("짧습니다"))
                .anyMatch(m -> m.contains("숫자로만"))
                .anyMatch(m -> m.contains("흔한"));
        assertThat(policy.validate("password123", "hong_1", "홍길동")).anyMatch(m -> m.contains("흔한"));
    }

    @Test
    void similarToUsernameIsRejected() {
        assertThat(policy.validate("traveler_kim1", "traveler_kim", "김여행")).anyMatch(m -> m.contains("비슷"));
    }

    @Test
    void quickRatioMatchesPythonDifflib() {
        // difflib.SequenceMatcher(a="abcd", b="bcde").quick_ratio() == 0.75
        assertThat(PasswordPolicy.quickRatio("abcd", "bcde")).isEqualTo(0.75);
        assertThat(PasswordPolicy.quickRatio("", "")).isEqualTo(1.0);
    }
}
