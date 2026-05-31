package com.gb.community.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc(OpenAPI) 설정. community-service의 Swagger 문서 제목/설명을 지정한다.
 *
 * <p>인증(Authentik/OIDC) 미구현이라 컨트롤러는 {@code X-User-Public-Id} 헤더로 임시 인증 처리 중이다.
 * Swagger UI에서 이 헤더를 입력해 테스트할 수 있도록, 헤더를 명시 선언하지 않은 오퍼레이션에도
 * 글로벌 헤더 파라미터로 추가한다(wallet-service SwaggerConfig 패턴과 동일).
 *
 * <p>TODO: 인증 구현 후 이 글로벌 헤더 파라미터를 제거하고 OAuth2 Bearer 보안 스키마로 교체.
 */
@Configuration
public class SwaggerConfig {

    private static final String USER_HEADER = "X-User-Public-Id";

    @Bean
    public OpenAPI communityOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Community Service API")
                        .description("커뮤니티(게시글/댓글) API")
                        .version("v1"));
    }

    /**
     * 모든 오퍼레이션에 {@code X-User-Public-Id} 헤더 입력란을 추가한다. 컨트롤러가 이미
     * {@code @RequestHeader}로 선언한 오퍼레이션은 springdoc이 자동 문서화하므로 중복 추가를 피한다.
     */
    @Bean
    public OperationCustomizer userPublicIdHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            boolean alreadyDeclared = operation.getParameters() != null
                    && operation.getParameters().stream()
                            .anyMatch(p -> USER_HEADER.equals(p.getName()) && "header".equals(p.getIn()));
            if (!alreadyDeclared) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name(USER_HEADER)
                        .description("임시 인증용 사용자 public_id (UUID). 인증 구현 후 제거 예정.")
                        .required(false)
                        .schema(new StringSchema()));
            }
            return operation;
        };
    }
}