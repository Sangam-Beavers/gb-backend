package com.gb.document.domain.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 대화 이력 조회 응답 — 재방문 복원용. 정본: ai-chatbot-mcp.md §6-2, api-spec.md §7.
 *
 * <p>챗봇 Lambda {@code /history}의 응답(snake_case JSON)과 프론트 응답이 같은 형태라 하나로 쓴다.
 * 역직렬화(Lambda→백엔드)·직렬화(백엔드→프론트) 모두 전역 SNAKE_CASE 설정으로 처리된다.
 *
 * <p>{@code visible=true} 턴만 내려온다 — 첫 턴에 주입된 합성 분석요약 턴은 Lambda가 걸러서
 * 화면에 노출되지 않는다(§7).
 */
public record ChatHistoryResponse(
        @Schema(description = "대화 메시지 목록(시간 오름차순). 이력 없으면 빈 배열")
        List<ChatHistoryMessage> messages,

        @Schema(description = "다음 페이지 커서(base64). 마지막 페이지면 null", nullable = true)
        String nextCursor
) {

    public record ChatHistoryMessage(
            @Schema(description = "발화 주체", allowableValues = {"user", "assistant"}, example = "assistant")
            String role,

            @Schema(description = "메시지 텍스트", example = "네, 계약서상 월 급여는 최저임금에 미달합니다...")
            String content,

            @Schema(description = "생성 시각 (ISO 8601 UTC Z)", example = "2026-06-05T09:12:45Z")
            String createdAt
    ) {
    }
}
