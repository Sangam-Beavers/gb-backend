package com.gb.document.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
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
 * <p>인증은 OAuth2 Resource Server(방식 B)로, 모든 요청에 IdP(개발=Authentik, 운영=Cognito)가 발급한
 * Bearer JWT가 필요하다(문서/헬스체크 제외). Swagger UI의 "Authorize" 버튼에 액세스 토큰을 넣어 보호
 * 엔드포인트를 호출할 수 있도록 Bearer 보안 스키마를 전역으로 선언한다. 본인 식별자는 토큰
 * {@code public_id} claim에서 추출되며, 더 이상 {@code X-User-Public-Id} 헤더를 입력하지 않는다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI documentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Document Service API")
                        .description("문서 분석 API (제출/상태/결과/목록) + 분석 결과 후속 질문 챗봇(SSE 스트리밍)")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * Swagger 스키마/예시의 속성명을 런타임 직렬화와 동일하게 snake_case로 렌더링한다.
     *
     * <p>springdoc 기본 ModelResolver는 전역 Jackson PropertyNamingStrategy(SNAKE_CASE, application.yaml)를
     * 반영하지 않아, 예시 본문이 camelCase(예: {@code userLang})로 떠 그대로 보내면 서버는 {@code user_lang}을
     * 기대하므로 검증 실패(400)한다. Spring이 구성한 ObjectMapper로 ModelResolver를 교체하면 모든 DTO
     * 스키마/예시가 snake_case({@code user_lang})로 표시돼 복붙만으로 요청이 통과한다.
     */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }
}
