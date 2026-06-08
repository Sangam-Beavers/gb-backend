package com.gb.community.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.signer.Aws4Signer;

/**
 * 번역 Lambda 호출 인프라 빈(HTTP 클라이언트 + SigV4 서명기) 등록.
 *
 * <p>stage·prod 프로파일에서만 활성화 — dev/test는 {@code MockTranslationClient}가 활성화되어
 * 이 빈들이 필요 없다. 분리해 두면 dev/test 컨텍스트 로딩 시 불필요한 자격 증명 조회/네트워크 의존이 없다.
 *
 * <p>현재 {@link com.gb.community.global.client.BedrockTranslationClient}는 자체 생성자에서
 * {@code HttpClient}/{@code Aws4Signer}/{@code DefaultCredentialsProvider}를 직접 만들기에 본 Config의
 * 빈들이 강제 의존은 아니다. 다만 외부 주입(타임아웃 튜닝·테스트 대체)을 위해 빈으로 노출해 둔다 —
 * 향후 BedrockTranslationClient가 생성자 주입으로 받도록 리팩터링할 때 본 Config를 활용한다.
 */
@Configuration
@Profile("!dev & !test")
public class TranslationClientConfig {

    /**
     * AWS SDK SigV4 서명기. 무상태(thread-safe)라 싱글톤으로 둬도 안전하다.
     */
    @Bean
    public Aws4Signer translationAws4Signer() {
        return Aws4Signer.create();
    }

    /**
     * 번역 Lambda 호출용 JDK {@link HttpClient}. SigV4 서명이 본문 해시에 묶여 RestClient를 쓸 때
     * 헤더 추가/본문 변환에 민감해, 어떤 변환도 끼지 않는 JDK HttpClient를 직접 쓴다.
     */
    @Bean
    public HttpClient translationHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
