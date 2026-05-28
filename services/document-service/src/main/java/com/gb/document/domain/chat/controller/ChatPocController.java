package com.gb.document.domain.chat.controller;

import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.dto.request.PocChatRequest;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * R1 PoC 컨트롤러 — ai-chatbot-mcp.md §12 R1 (Function URL ↔ SseEmitter 중계) 검증 전용.
 *
 * <p>실제 챗봇 엔드포인트({@code POST /api/v1/documents/{id}/chat}, §6)로 승격되기 전,
 * 권한 검증·분석요약 추출 등 부수 로직 없이 <b>스트리밍 중계만</b> 동작하는지 확인한다.
 *
 * <p>Phase 3에서 이 컨트롤러는 삭제되고, {@code ChatController}로 대체된다.
 *
 * <h3>임시 처리</h3>
 * <ul>
 *   <li>인증 미구현 — {@code X-User-Public-Id} 헤더 임시 수신(CLAUDE.md §9)</li>
 *   <li>document_public_id는 dummy 값(R1은 권한 검증 미수행)</li>
 *   <li>analysis_summary는 하드코딩(R1은 MySQL 미연결)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/poc")
@RequiredArgsConstructor
public class ChatPocController {

    private final ChatbotLambdaClient chatbotLambdaClient;

    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            // TODO: 인증 구현 후 JWT(sub)에서 userPublicId 추출로 교체. 현재는 헤더 임시 수신(CLAUDE.md §9).
            @RequestHeader(value = "X-User-Public-Id", required = false) String userPublicId,
            @Valid @RequestBody PocChatRequest request
    ) {
        log.info("[R1-PoC] chatStream userPublicId={} message={}", userPublicId, request.message());

        // SSE 타임아웃: 챗봇 답변 길이를 감안해 넉넉히. Phase 3에서 application.yaml로 빼낸다.
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onCompletion(() -> log.debug("[R1-PoC] emitter completed"));
        emitter.onTimeout(() -> log.warn("[R1-PoC] emitter timeout"));
        emitter.onError(e -> log.warn("[R1-PoC] emitter error", e));

        ChatbotPayload payload = ChatbotPayload.builder()
                .message(request.message())
                .sessionId(UUID.randomUUID().toString())
                .userLang(request.userLangOrDefault())
                .documentPublicId("poc-document")
                .userPublicId(userPublicId != null ? userPublicId : "anonymous")
                // R1 PoC 단계 더미값 — 진짜는 application.yaml의 source/environment 주입(ai-chatbot-mcp §6).
                .source("development")
                .environment("dev")
                .analysisSummary("위험도 HIGH. 최저임금 미달(월 160만원, 기준 209만원), 주 50시간 초과근무. 문서유형: 근로계약서.")
                .build();

        // 블로킹 호출이라 별도 스레드로. ChatbotLambdaClient가 끝까지 스트림을 읽는 동안
        // 메인 요청 스레드는 SseEmitter만 반환하고 즉시 풀려난다.
        CompletableFuture.runAsync(() -> chatbotLambdaClient.streamChat(payload, new SseRelayListener(emitter)));

        return emitter;
    }

    /**
     * {@link ChatStreamListener} 구현 — Lambda 콜백을 {@link SseEmitter} 이벤트로 중계.
     * onToken/onDone은 SSE 이벤트로, onError는 emitter.completeWithError로 변환.
     */
    private static final class SseRelayListener implements ChatStreamListener {
        private final SseEmitter emitter;

        SseRelayListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onToken(String text) {
            try {
                emitter.send(SseEmitter.event().name("token").data(text));
            } catch (IOException e) {
                // 클라이언트가 끊은 경우 등. emitter는 이미 망가졌으므로 completeWithError 안전.
                emitter.completeWithError(e);
            }
        }

        @Override
        public void onDone(String sessionId) {
            try {
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of("session_id", sessionId)));
            } catch (IOException ignored) {
                // 종료 직전이라 무시
            }
            emitter.complete();
        }

        @Override
        public void onError(Throwable t) {
            emitter.completeWithError(t);
        }
    }
}
