package com.gb.member.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * app-admin-service 호출용 {@link RestClient} 빈(이슈 #244).
 * {@link WalletClientConfig}/{@link IdpClientConfig}와 동일 패턴 — connect/read 타임아웃 명시.
 *
 * <p>가입 시 DOC_ANALYSIS_CREDIT 설정을 단건 조회하는 용도이므로 read 타임아웃은 5s로 짧게 둔다.
 * 조회 실패 시 {@link com.gb.member.global.client.RealAppAdminClient}가 기본값(3)으로 fail-open하므로
 * 타임아웃이 가입 흐름 전체를 막지 않는다.
 *
 * <p>{@code @Profile("!test")}: MockAppAdminClient가 test에서 대신 쓰이므로 빈 충돌을 방지한다.
 */
@Configuration
@Profile("!test")
public class AppAdminClientConfig {

    @Bean
    public RestClient appAdminRestClient(
            RestClient.Builder builder,
            @Value("${app-admin.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app-admin.api.read-timeout:5s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .requestFactory(requestFactory)
                .build();
    }
}
