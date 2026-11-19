package com.misterworldtour.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * User Interface 설정 (mwt.*).
 *
 * @param staffSignupCode 직원 가입 인증 코드 (.env STAFF_SIGNUP_CODE)
 */
@ConfigurationProperties(prefix = "mwt")
public record UiProperties(@DefaultValue("MWT-STAFF-2026") String staffSignupCode) {
}
