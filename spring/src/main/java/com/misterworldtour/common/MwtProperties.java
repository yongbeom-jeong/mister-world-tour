package com.misterworldtour.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 프로젝트 설정 (application.yml 의 mwt.*). 비밀값은 .env 에만 두고 여기서는 이름으로만 읽는다.
 *
 * @param staffSignupCode 직원 유형 가입 인증 코드 (임시 정책, .env STAFF_SIGNUP_CODE)
 * @param mediaRoot       직원이 올린 상품 사진 폴더 (/media/ 로 제공. 운영에서는 Nginx 가 제공해도 된다)
 * @param timeZone        업무 날짜 기준 시간대 (출발일·완료일 판정)
 */
@ConfigurationProperties(prefix = "mwt")
public record MwtProperties(
        @DefaultValue("MWT-STAFF-2026") String staffSignupCode,
        @DefaultValue("media") String mediaRoot,
        @DefaultValue("Asia/Seoul") String timeZone) {
}
