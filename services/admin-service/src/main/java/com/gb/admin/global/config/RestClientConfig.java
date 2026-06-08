package com.gb.admin.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 모니터링 용도 RestClient — 다른 서비스 /actuator/health 호출 timeout이 짧아야 발표 화면이 멈추지 않는다.
 *
 * <p>{@code admin.service-health.timeout-ms}로 connect/read 타임아웃을 동시에 통제. 패턴은 wallet의
 * {@code MemberClientConfig}와 동일(JDK HttpClient + JdkClientHttpRequestFactory).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient monitoringRestClient(RestClient.Builder builder, ServiceHealthProperties props) {
        Duration timeout = Duration.ofMillis(props.timeoutMs());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder
                .requestFactory(requestFactory)
                .build();
    }
}
