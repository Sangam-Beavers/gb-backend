package com.gb.wallet.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * member-service 호출용 {@link RestClient} 빈. connect/read 타임아웃을 명시한다.
 *
 * <p>{@link BankClientConfig}와 동일 계열 패턴 — 타임아웃이 없으면 member-service가 응답을 멈출 때
 * 호출 스레드가 무한 대기한다(특히 readOnly tx 안에서 호출되는 recent-recipients/receipt 경로는
 * DB 커넥션 점유가 길어진다). 타임아웃 만료 시 {@link org.springframework.web.client.RestClientException}이
 * 떨어지고, 표시용(getMember)은 {@code RealMemberClient}가 fail-open("Unknown" 폴백)으로,
 * 검증용(findByEmail)은 COMMON5000으로 fail-fast 처리한다.
 *
 * <p>baseUrl은 설정하지 않는다 — {@code RealMemberClient}가 호출마다 절대 URI({@code member.api.base-url}+경로)를
 * 만든다(BankClientConfig와 달리 base-url 프로퍼티를 빈에 박지 않아, 클라이언트 빈이 없는 test
 * 프로파일은 프로퍼티가 필요 없다). 환경별 튜닝은 {@code member.api.connect-timeout}/{@code member.api.read-timeout}.
 */
@Configuration
public class MemberClientConfig {

    @Bean
    public RestClient memberRestClient(
            RestClient.Builder builder,
            @Value("${member.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${member.api.read-timeout:10s}") Duration readTimeout) {
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
