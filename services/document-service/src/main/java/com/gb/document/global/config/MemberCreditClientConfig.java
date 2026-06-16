package com.gb.document.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * member-service 크레딧 API 호출용 RestClient 빈 구성.
 *
 * <p>테스트 프로파일에서는 {@link com.gb.document.global.client.member.MockMemberCreditClient}가
 * 빈을 대체하므로 이 설정을 로드하지 않는다.
 *
 * <p><b>request factory는 반드시 PATCH를 지원하는 구현이어야 한다.</b> 크레딧 차감은
 * {@code PATCH /api/v1/internal/members/{id}/credit/use}인데, Boot 기본
 * {@code SimpleClientHttpRequestFactory}(JDK {@link java.net.HttpURLConnection})는 PATCH를 막아
 * {@code ProtocolException: Invalid HTTP method: PATCH}로 모든 제출이 COMMON5000(500)이 된다.
 * 클래스패스에 Apache HttpClient 등이 없을 때 DEFAULTS가 Simple로 떨어지므로, PATCH를 지원하는
 * JDK {@link java.net.http.HttpClient} 기반 {@link JdkClientHttpRequestFactory}를 명시적으로 쓴다.
 */
@Configuration
@Profile("!test")
public class MemberCreditClientConfig {

    @Bean
    public RestClient memberCreditRestClient(
            RestClient.Builder builder,
            @Value("${member.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${member.api.read-timeout:5s}") Duration readTimeout) {

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
