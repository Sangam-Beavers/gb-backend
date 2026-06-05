package com.gb.member.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * wallet-service 호출용 {@link RestClient} 빈(이슈 #152). connect/read 타임아웃을 명시한다.
 *
 * <p>{@link IdpClientConfig}와 동일 패턴 — 타임아웃이 없으면 wallet-service가 응답을 멈출 때
 * 신분증 인증 트랜잭션이 DB 락을 보유한 채 무한 대기해 Hikari 커넥션 풀이 고갈된다(DoS).
 * 타임아웃 만료 시 {@link org.springframework.web.client.RestClientException}이 떨어지고
 * {@code RealWalletClient}의 catch 블록이 {@code COMMON5000}으로 매핑해 호출 측이 fail-open으로 흡수한다.
 *
 * <p>baseUrl은 설정하지 않는다 — {@code RealWalletClient}가 호출마다 절대 URI({@code wallet.api.base-url}+경로)를
 * 만든다. 기본값은 {@code @Value} 디폴트(connect 3s / read 10s)로 두고, 환경별 튜닝은
 * {@code wallet.api.connect-timeout}/{@code wallet.api.read-timeout}로 덮어쓴다.
 */
@Configuration
public class WalletClientConfig {

    @Bean
    public RestClient walletRestClient(
            RestClient.Builder builder,
            @Value("${wallet.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${wallet.api.read-timeout:10s}") Duration readTimeout) {
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
