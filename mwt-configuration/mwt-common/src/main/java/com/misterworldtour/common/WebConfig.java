package com.misterworldtour.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * 웹 공통 설정. 화면 인터셉터(ui.LayoutModelInterceptor 등)는 User Interface 가 빈으로 제공하고 여기서는
 * {@link HandlerInterceptor} 타입으로만 받아 등록한다 (common 은 ui 를 참조하지 않는다).
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final MwtProperties properties;
    private final List<HandlerInterceptor> interceptors;

    WebConfig(MwtProperties properties, List<HandlerInterceptor> interceptors) {
        this.properties = properties;
        this.interceptors = interceptors;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        interceptors.forEach(registry::addInterceptor);
    }

    /** 직원이 올린 상품 사진 (/media/ 로 제공). */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(properties.mediaRoot()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/media/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
    }

    /** 오늘 날짜의 기준 (Asia/Seoul). 테스트에서는 고정 시계로 바꾼다. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of(properties.timeZone()));
    }
}
