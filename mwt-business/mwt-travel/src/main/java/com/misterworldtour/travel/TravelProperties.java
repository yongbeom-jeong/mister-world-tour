package com.misterworldtour.travel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Travel Management 설정 (application.yml 의 mwt.*).
 *
 * @param mediaRoot 직원이 올린 상품 사진 폴더 (/media/ 로 제공. 운영에서는 Nginx 가 제공한다)
 */
@ConfigurationProperties(prefix = "mwt")
public record TravelProperties(@DefaultValue("media") String mediaRoot) {
}
