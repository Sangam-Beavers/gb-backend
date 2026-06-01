package com.gb.community.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc(OpenAPI) 설정. community-service의 Swagger 문서 제목/설명을 지정한다.
 *
 * <p>인증은 OAuth2 Resource Server(방식 B)로, 모든 요청에 IdP가 발급한 Bearer JWT가 필요하다
 * (문서/헬스체크 제외). Swagger UI의 "Authorize" 버튼에 액세스 토큰을 넣어 보호 엔드포인트를 호출할 수 있도록
 * Bearer 보안 스키마를 전역으로 선언한다. 본인 식별자는 토큰 {@code public_id} claim에서 추출되며,
 * 더 이상 {@code X-User-Public-Id} 헤더를 입력하지 않는다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI communityOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Community Service API")
                        .description("커뮤니티(게시글/댓글) API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}