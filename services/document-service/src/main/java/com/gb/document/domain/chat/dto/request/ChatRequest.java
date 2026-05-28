package com.gb.document.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 후속 질문 챗봇 요청 DTO. 정본: ai-chatbot-mcp.md §6.
 *
 * <p>전역 SNAKE_CASE 설정으로 JSON 필드는 {@code session_id / user_lang}으로 자동 변환된다.
 *
 * @param message  사용자 질문 (필수)
 * @param sessionId 없으면 백엔드가 새 UUID 발급. 이어가는 대화면 클라이언트가 직전 응답의 session_id 전달.
 * @param userLang 답변 언어 코드 (필수, 데모 {@code "ko"}).
 */
public record ChatRequest(
        @NotBlank String message,
        String sessionId,
        @NotBlank String userLang
) {
}
