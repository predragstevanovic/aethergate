package com.aethergate.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Console endpoints get role checks and auditing; the OpenAI-compatible gateway ({@code /v1}) uses API keys instead. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roles;

    public WebConfig(RoleInterceptor roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roles).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/auth/**");
    }
}
