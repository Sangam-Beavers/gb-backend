package com.gb.community.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 번역 Lambda 호출 관련 설정 (현재 빈 없음).
 *
 * <p>이전에는 SigV4 직접 서명용 {@code Aws4Signer} + JDK {@code HttpClient} 빈을 노출했으나,
 * EKS에서 Function URL(.on.aws) 경로가 인가 거부되는 이슈로 호출을 <b>표준 Lambda Invoke API</b>로 전환하면서
 * ({@link com.gb.community.global.client.BedrockTranslationClient}가 {@code LambdaClient}를 직접 생성)
 * 두 빈이 불필요해져 제거했다.
 *
 * <p>현재 등록하는 빈이 없으므로 본 클래스는 삭제 후보다(SSOT 정리 시 함께 제거 검토 — CLAUDE.md §12 삭제 규칙).
 */
@Configuration
@ConditionalOnProperty(name = "translation.client", havingValue = "bedrock")
public class TranslationClientConfig {
}
