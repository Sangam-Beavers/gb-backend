package com.gb.admin.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Real{X}AdminClient 들이 공유하는 RestClient 빈.
 *
 * <p>connect/read 타임아웃은 admin.internal-api.* 에서 받는다. 인증 헤더는 부착하지 않는다 —
 * 도메인 서비스의 /internal/admin/* 가 permitAll 로 받기 때문. 다음 스프린트에 mTLS·NetworkPolicy 도입.
 */
@Configuration
public class InternalApiRestClientConfig {

    @Bean
    public RestClient internalApiRestClient(RestClient.Builder builder, InternalApiProperties props) {
        Duration connect = Duration.ofMillis(props.connectTimeoutMs() <= 0 ? 3000 : props.connectTimeoutMs());
        Duration read = Duration.ofMillis(props.readTimeoutMs() <= 0 ? 10000 : props.readTimeoutMs());
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(read);
        return builder.requestFactory(rf).build();
    }
}
