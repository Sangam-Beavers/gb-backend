package com.gb.document.domain.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * 백엔드 → 챗봇 Lambda Function URL 페이로드.
 * 정본: docs/document-analysis/ai-chatbot-mcp.md §6 "백엔드 → 챗봇 Lambda 페이로드".
 *
 * <p>JSON 필드는 snake_case (Jackson SNAKE_CASE 전역 설정으로 자동 변환).
 * <p>{@code source}와 {@code environment}는 역할이 다르므로 혼동 금지:
 * <ul>
 *   <li>{@code source} = development|production  (인프라 계열 분기, 분석 파이프라인과 동일)
 *   <li>{@code environment} = dev|stage|prod   (MCP URL 라우팅용 3값)
 * </ul>
 * <p>{@code analysisSummary}는 <b>첫 대화에만</b> 채운다(전문 아닌 압축 요약).
 * 이후 턴에서는 null/생략 — 요약은 이미 messages/DynamoDB 맥락에 묻어있다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatbotPayload(
        String message,
        String sessionId,
        String userLang,
        String documentPublicId,
        String userPublicId,
        String source,
        String environment,
        String analysisSummary
) {
}
