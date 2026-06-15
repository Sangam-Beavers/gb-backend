package com.gb.document.global.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * member-service 크레딧 API 호출용 RestClient 빈 구성.
 *
 * <p>테스트 프로파일에서는 {@link com.gb.document.global.client.member.MockMemberCreditClient}가
 * 빈을 대체하므로 이 설정을 로드하지 않는다.
 */
@Configuration
@Profile("!test")
public class MemberCreditClientConfig {

    @Bean
    public RestClient memberCreditRestClient(
            RestClient.Builder builder,
            @Value("${member.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${member.api.read-timeout:5s}") Duration readTimeout) {

        return builder
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)))
                .build();
    }
}
