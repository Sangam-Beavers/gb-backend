package com.gb.wallet.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Mock 은행(Beaver/Quokka Bank) 호출용 {@link RestClient} 빈 설정.
 *
 * <p>Base URL은 환경변수 {@code BANK_API_BASE_URL}을 통해 주입되며 환경별로(개발/스테이지/운영) 다르다.
 * 실서비스 전환 시에도 이 빈은 그대로 두고 {@code RealBankClient}만 추가하면 된다 (URL만 교체).
 *
 * <p><b>타임아웃:</b> connect/read 타임아웃을 명시한다. 없으면 Mock 은행이 응답을 멈출 때 wallet-service
 * 스레드가 무한 대기한다. 값이 만료되면 {@link java.net.SocketTimeoutException}이 떨어지고
 * {@code MockBankClient}의 {@code ResourceAccessException} 분기가 잡아 {@code COMMON5031}로 매핑한다 —
 * 즉 타임아웃을 설정해야 우리 에러 매핑이 의미를 가진다. 기본값은 {@code @Value} 디폴트로 두고,
 * 환경별 튜닝은 {@code bank.api.connect-timeout}/{@code bank.api.read-timeout} 프로퍼티로 덮어쓴다.
 *
 * <p>TODO: {@code TLS_ENABLED=true}일 때 클라이언트 인증서(keystore.p12)·truststore 기반 mTLS 설정 추가.
 */
@Configuration
public class BankClientConfig {

    /**
     * Spring Boot가 제공하는 {@link RestClient.Builder}(설정된 HttpMessageConverters + Jackson ObjectMapper 포함)를
     * 주입받아 사용한다. 이렇게 해야 전역 Jackson 설정({@code spring.jackson.property-naming-strategy: SNAKE_CASE})이
     * Mock 은행 응답 직렬화에도 적용된다 ({@code RestClient.builder()} 정적 호출은 기본 ObjectMapper만 사용).
     */
    @Bean
    public RestClient bankRestClient(
            RestClient.Builder builder,
            @Value("${bank.api.base-url}") String baseUrl,
            @Value("${bank.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${bank.api.read-timeout:10s}") Duration readTimeout) {
        // JDK HttpClient는 connect 타임아웃을 HttpClient 생성 시점에 받고,
        // read(=request) 타임아웃은 request factory 쪽에서 받는다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
