package com.mealmind.biz.config;

import com.mealmind.biz.session.SessionInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：把会话拦截器挂到 /api/** 上。
 * 公开接口（游客登录、ping）与 Actuator 不鉴权。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SessionInterceptor sessionInterceptor;

    public WebMvcConfig(SessionInterceptor sessionInterceptor) {
        this.sessionInterceptor = sessionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/session/guest", "/api/ping");
    }
}
