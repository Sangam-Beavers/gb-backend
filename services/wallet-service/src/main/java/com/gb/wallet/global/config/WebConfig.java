package com.gb.wallet.global.config;

import com.gb.wallet.global.security.CurrentUserPublicIdArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 설정 — 커스텀 ArgumentResolver 등록.
 *
 * <p>{@link CurrentUserPublicIdArgumentResolver}를 등록해 컨트롤러가
 * {@code @CurrentUserPublicId String userPublicId}로 인증 사용자 식별자를 받을 수 있게 한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserPublicIdArgumentResolver currentUserPublicIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserPublicIdArgumentResolver);
    }
}