package com.gb.admin.global.config;

import com.gb.admin.global.security.CurrentAdminPublicIdArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 설정 — 커스텀 ArgumentResolver 등록.
 *
 * <p>{@link CurrentAdminPublicIdArgumentResolver}를 등록해 컨트롤러가
 * {@code @CurrentAdminPublicId String adminPublicId}로 인증된 관리자 식별자를 받을 수 있게 한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentAdminPublicIdArgumentResolver currentAdminPublicIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAdminPublicIdArgumentResolver);
    }
}
