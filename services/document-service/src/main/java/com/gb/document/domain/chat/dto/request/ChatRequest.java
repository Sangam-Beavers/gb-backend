package com.gb.document.domain.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 후속 질문 챗봇 요청 DTO. 정본: ai-chatbot-mcp.md §6.
 *
 * <p>전역 SNAKE_CASE 설정으로 JSON 필드는 {@code session_id / user_lang}으로 자동 변환된다.
 *
 * @param message   사용자 질문 (필수)
 * @param sessionId 없으면 백엔드가 새 UUID 발급. 이어가는 대화면 클라이언트가 직전 응답의 session_id 전달.
 * @param userLang  답변 언어 코드 (필수, 데모 {@code "ko"}).
 */
public record ChatRequest(
        @Schema(description = "사용자 질문", example = "이 계약서 최저임금 위반인가요?",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String message,

        @Schema(description = "대화 세션 식별자. 없으면 백엔드가 새 UUID를 발급하고, 이어가는 대화면 직전 응답의 "
                + "session_id를 그대로 전달한다.",
                example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String sessionId,

        @Schema(description = "답변 언어 코드 (ISO 639-1). 데모는 ko.", example = "ko",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String userLang
) {
}
