package com.misterworldtour.ui;

import com.misterworldtour.travel.TravelProperties;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.List;

/** 웹 설정: 화면 인터셉터({@link LayoutModelInterceptor} 등) 등록과 업로드 사진(/media/) 제공. */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final TravelProperties properties;
    private final List<HandlerInterceptor> interceptors;

    WebConfig(TravelProperties properties, List<HandlerInterceptor> interceptors) {
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
}
