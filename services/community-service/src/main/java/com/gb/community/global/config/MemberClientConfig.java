package com.gb.community.global.config;

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
 * <p>member-service의 {@code WalletClientConfig}와 동일 패턴 — 타임아웃이 없으면 member-service가
 * 응답을 멈출 때 게시글/댓글 조회 스레드가 무한 대기한다. 타임아웃 만료 시
 * {@link org.springframework.web.client.RestClientException}이 떨어지고, 표시용 조회라
 * {@code RealMemberClient}가 fail-open으로 흡수해 "Unknown" 폴백으로 응답한다(5xx로 새지 않음).
 *
 * <p>baseUrl은 설정하지 않는다 — {@code RealMemberClient}가 호출마다 절대 URI({@code member.api.base-url}+경로)를
 * 만든다(base-url 프로퍼티는 프로파일 게이트된 클라이언트 빈에만 주입돼, 빈이 없는 test 프로파일은
 * 프로퍼티가 필요 없다). 기본값은 {@code @Value} 디폴트(connect 3s / read 10s)로 두고, 환경별 튜닝은
 * {@code member.api.connect-timeout}/{@code member.api.read-timeout}로 덮어쓴다.
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
