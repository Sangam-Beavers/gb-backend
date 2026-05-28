package com.gb.document.domain.chat.controller;

import com.gb.document.domain.chat.dto.request.ChatRequest;
import com.gb.document.domain.chat.service.ChatService;
import com.gb.document.domain.chat.util.SseRelayListener;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * 후속 질문 챗봇 컨트롤러 — ai-chatbot-mcp.md §6 본 엔드포인트.
 *
 * <pre>POST /api/v1/documents/{publicId}/chat</pre>
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>{@link ChatService#verifyOwnership}을 <b>동기</b>로 호출 — 실패 시 BusinessException이
 *       GlobalExceptionHandler에 잡혀 HTTP 4xx + 공통 에러 envelope으로 응답된다(404 DOCUMENT4001,
 *       403 COMMON4031). SSE 시작 전이라 HTTP 상태 코드를 정상적으로 줄 수 있다.</li>
 *   <li>권한 통과 후 {@link SseEmitter} 생성. Lambda 호출은 블로킹이라 별도 스레드에서 실행.</li>
 *   <li>{@link SseRelayListener}가 Lambda 토큰을 SSE 이벤트(token/done)로 변환해 흘림.</li>
 * </ol>
 *
 * <h3>임시 처리 (인증 미구현)</h3>
 * CLAUDE.md §9 / conventions.md §14 — JWT 인증 도입 전까지 {@code X-User-Public-Id} 헤더로 임시 수신.
 * 인증 확정 시 이 부분만 JWT(sub) 추출로 교체.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/{publicId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @PathVariable("publicId") String documentPublicId,
            // TODO: 인증 구현 후 JWT(sub)에서 userPublicId 추출로 교체 (CLAUDE.md §9).
            @RequestHeader("X-User-Public-Id") String userPublicId,
            @Valid @RequestBody ChatRequest request
    ) {
        log.info("[chat] documentPublicId={} userPublicId={} firstTurn={}",
                documentPublicId, userPublicId, request.sessionId() == null);

        // 1) 권한 검증 — 동기. 실패 시 throw → GlobalExceptionHandler가 HTTP 4xx 응답.
        chatService.verifyOwnership(documentPublicId, userPublicId);

        // 2) SseEmitter 생성 + 콜백.
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onCompletion(() -> log.debug("[chat] emitter completed"));
        emitter.onTimeout(() -> log.warn("[chat] emitter timeout"));
        emitter.onError(e -> log.warn("[chat] emitter error", e));

        // 3) Lambda 호출은 블로킹이라 별도 스레드. SseRelayListener가 토큰을 SSE 이벤트로 중계.
        CompletableFuture.runAsync(() -> {
            try {
                chatService.streamChat(documentPublicId, userPublicId, request,
                        new SseRelayListener(emitter));
            } catch (Exception e) {
                // verifyOwnership은 이미 통과한 상태라 여기 오는 예외는 페이로드 조립/Lambda 호출 단계의 시스템 오류.
                log.warn("[chat] streamChat failed", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
