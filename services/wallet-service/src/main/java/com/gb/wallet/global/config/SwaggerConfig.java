package com.gb.wallet.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc(OpenAPI) 설정.
 *
 * <p>인증(Authentik/OIDC)이 미구현이라, 컨트롤러는 {@code X-User-Public-Id} 헤더로 임시 인증 처리 중이다.
 * Swagger UI에서 이 헤더를 입력해 테스트할 수 있도록 모든 오퍼레이션에 글로벌 헤더 파라미터로 추가한다.
 *
 * <p>TODO: 인증 구현 후 이 글로벌 헤더 파라미터를 제거하고, OAuth2 Bearer 보안 스키마로 교체할 것.
 */
@Configuration
public class SwaggerConfig {

    /** 인증 미구현 동안 임시로 사용하는 사용자 식별 헤더명. */
    private static final String USER_HEADER = "X-User-Public-Id";

    @Bean
    public OpenAPI walletOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet Service API")
                        .description("전자지갑/잔액 API")
                        .version("v1"));
    }

    /**
     * 모든 오퍼레이션에 {@code X-User-Public-Id} 헤더 입력란을 추가한다.
     * 인증 구현 전까지 Swagger UI에서 사용자 식별자를 직접 넣어 호출 테스트를 할 수 있게 한다.
     *
     * <p>컨트롤러가 이미 {@code @RequestHeader("X-User-Public-Id")}로 선언한 오퍼레이션은
     * springdoc이 자동으로 해당 헤더를 문서화하므로, 중복 추가를 피하기 위해 건너뛴다.
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
                        .schema(new io.swagger.v3.oas.models.media.StringSchema()));
            }
            return operation;
        };
    }
}
