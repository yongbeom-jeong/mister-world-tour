package com.misterworldtour.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * User Interface 설정 (application.yml 의 mwt.*). 비밀값은 .env 에만 두고 여기서는 이름으로만 읽는다.
 *
 * @param staffSignupCode 직원 유형 가입 인증 코드 (임시 정책, .env STAFF_SIGNUP_CODE)
 */
@ConfigurationProperties(prefix = "mwt")
public record UiProperties(@DefaultValue("MWT-STAFF-2026") String staffSignupCode) {
}
