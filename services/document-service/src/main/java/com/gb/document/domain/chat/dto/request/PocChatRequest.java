package com.gb.document.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * R1 PoC 단계 임시 요청 DTO. Phase 3에서 진짜 ChatRequest로 승격될 때
 * documentPublicId는 URL path variable로 들어오고 sessionId 등이 추가된다.
 *
 * <p>JSON 필드는 snake_case로 자동 변환(전역 SNAKE_CASE 설정 가정):
 * {@code userLang → user_lang}.
 */
public record PocChatRequest(
        @NotBlank String message,
        String userLang
) {
    public String userLangOrDefault() {
        return (userLang == null || userLang.isBlank()) ? "ko" : userLang;
    }
}
