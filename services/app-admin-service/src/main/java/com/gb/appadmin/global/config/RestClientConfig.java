package com.gb.appadmin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * member-service 내부 API 호출용 RestClient.
     * base URL은 환경변수 MEMBER_INTERNAL_URL 로 주입.
     */
    @Bean
    public RestClient memberRestClient(
            @Value("${app-admin.member-service.base-url}") String memberBaseUrl) {
        return RestClient.builder()
                .baseUrl(memberBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * community-service 내부 API 호출용 RestClient.
     * base URL은 환경변수 COMMUNITY_INTERNAL_URL 로 주입.
     */
    @Bean
    public RestClient communityRestClient(
            @Value("${app-admin.community-service.base-url}") String communityBaseUrl) {
        return RestClient.builder()
                .baseUrl(communityBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
