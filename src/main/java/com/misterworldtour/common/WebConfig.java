package com.misterworldtour.common;

import com.misterworldtour.ui.LayoutModelInterceptor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final MwtProperties properties;

    WebConfig(MwtProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LayoutModelInterceptor());
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
