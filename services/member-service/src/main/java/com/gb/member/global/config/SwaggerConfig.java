package com.gb.member.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc(OpenAPI) 설정.
 * member-service의 Swagger 문서 제목/설명을 지정한다.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI memberOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Member Service API")
                        .description("회원/인증 API")
                        .version("v1"));
    }
}