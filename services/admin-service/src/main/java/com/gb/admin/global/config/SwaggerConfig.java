package com.gb.admin.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc(OpenAPI) 설정.
 *
 * <p>인증은 OAuth2 Resource Server(방식 B)로, 모든 요청에 IdP가 발급한 Bearer JWT가 필요하다
 * (문서/헬스체크 제외). Swagger UI의 "Authorize" 버튼에 액세스 토큰을 넣어 보호 엔드포인트를 호출할 수 있도록
 * Bearer 보안 스키마를 전역으로 선언한다. 본인 식별자는 토큰 {@code public_id} claim에서 추출된다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI adminOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Admin Service API")
                        .description("관리자 페이지(대시보드/모니터링/거래·회원·커뮤니티·문서 운영) API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
