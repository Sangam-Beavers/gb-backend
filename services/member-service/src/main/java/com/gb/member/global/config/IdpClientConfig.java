package com.gb.member.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 IdP(개발=Authentik) 관리 API 호출용 {@link RestClient} 빈. connect/read 타임아웃을 명시한다.
 *
 * <p><b>왜 필요한가(MEM1):</b> 타임아웃이 없으면 IdP가 응답을 멈출 때 가입(signup)·탈퇴(withdraw)의
 * {@code @Transactional} 흐름이 {@code saveAndFlush}로 잡은 UNIQUE 인덱스 락/행 락을 보유한 채 무한 대기해,
 * 그 스레드가 DB 커넥션을 붙든 상태로 쌓이면 Hikari 커넥션 풀이 고갈된다(DoS). 타임아웃이 만료되면
 * {@code RestClientException}(JDK {@link java.net.http.HttpTimeoutException} 래핑)이 떨어지고
 * {@code RealIdpUserClient}의 {@code RestClientException} 분기가 {@code COMMON5000}으로 매핑한다 — 즉
 * 타임아웃을 설정해야 그 에러 매핑·트랜잭션 롤백이 비로소 동작한다. wallet {@code BankClientConfig}와 동일 패턴.
 *
 * <p>baseUrl은 설정하지 않는다 — {@code RealIdpUserClient}가 호출마다 절대 URI({@code apiBaseUri}+경로)를
 * 만든다. 기본값은 {@code @Value} 디폴트(connect 3s / read 10s)로 두고, 환경별 튜닝은
 * {@code auth.idp.connect-timeout}/{@code auth.idp.read-timeout}로 덮어쓴다(env yml 변경 불필요).
 */
@Configuration
public class IdpClientConfig {

    /**
     * Spring Boot가 제공하는 {@link RestClient.Builder}(전역 Jackson SNAKE_CASE 등 설정 포함)를 주입받아
     * 사용한다 — {@code RestClient.builder()} 정적 호출은 기본 ObjectMapper만 써서 전역 설정을 잃는다.
     */
    @Bean
    public RestClient idpRestClient(
            RestClient.Builder builder,
            @Value("${auth.idp.connect-timeout:3s}") Duration connectTimeout,
            @Value("${auth.idp.read-timeout:10s}") Duration readTimeout) {
        // JDK HttpClient는 connect 타임아웃을 생성 시점에, read(=request) 타임아웃은 request factory에서 받는다.
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
