package com.gb.document.domain.chat.service;

import com.gb.document.domain.chat.dto.request.ChatRequest;

/**
 * 챗봇 본 서비스. 정본 흐름: ai-chatbot-mcp.md §6.
 *
 * <h3>역할 분리</h3>
 * <ul>
 *   <li>{@link #verifyOwnership} — <b>동기 권한 검증</b>. 컨트롤러가 SseEmitter 만들기 전에 호출해
 *       실패 시 GlobalExceptionHandler가 HTTP 4xx로 응답하도록 한다(BusinessException 전파).</li>
 *   <li>{@link #streamChat} — 권한 검증 후 <b>페이로드 조립 + Lambda 호출</b>. 블로킹이므로 컨트롤러가
 *       별도 스레드(CompletableFuture)에서 실행하고, 토큰은 listener로 흘려준다.</li>
 * </ul>
 *
 * <p>왜 검증을 service로 빼는가: CLAUDE.md §4 — 비즈니스 로직은 Service에. 컨트롤러는 HTTP 바인딩만.
 */
public interface ChatService {

    /**
     * 문서 존재 여부 + 소유자 일치 검증. 실패 시 {@link com.gb.common.exception.BusinessException}을 던진다.
     *
     * @throws com.gb.common.exception.BusinessException DOCUMENT4001 (문서 없음) / COMMON4031 (권한 없음)
     */
    void verifyOwnership(String documentPublicId, String userPublicId);

    /**
     * 챗봇 Lambda로 메시지 전송 + 응답 토큰을 listener로 흘림. <b>블로킹</b>이므로 별도 스레드에서 호출해야 한다.
     * 권한 검증은 이미 통과된 상태를 가정(별도 호출 없음).
     */
    void streamChat(String documentPublicId, String userPublicId,
                    ChatRequest request, ChatStreamListener listener);
}
