package com.gb.document.domain.chat.service;

import com.gb.document.domain.chat.dto.request.ChatbotPayload;

/**
 * 챗봇 Lambda(계정 B) Function URL을 IAM(SigV4)로 호출하고, Response Streaming으로
 * 돌아오는 SSE 토큰을 {@link ChatStreamListener}로 콜백한다.
 *
 * <p>정본 흐름: docs/document-analysis/ai-chatbot-mcp.md §6 — 백엔드 경유 SSE 중계.
 * <p>구현 시 가장 어려운 지점: ai-chatbot-mcp.md §12 <b>R1</b> (Function URL ↔ SseEmitter 중계).
 *
 * <h3>동기 / 블로킹</h3>
 * 이 메서드는 SSE 스트림을 끝까지 읽을 때까지 호출 스레드를 점유한다. 컨트롤러는
 * 이 메서드를 별도 Executor에서 실행하고, 메인 스레드는 SseEmitter를 먼저 반환해야 한다.
 *
 * <h3>로컬 모킹 모드</h3>
 * Function URL host가 localhost/127.0.0.1이면 IAM 서명을 스킵한다.
 * (R1 PoC 단계에서 AWS 배포 전 Spring side 단독 검증용 — poc/r1-stream/local-mock-server.py 사용)
 */
public interface ChatbotLambdaClient {

    /**
     * 페이로드를 Function URL에 POST하고, SSE 응답에서 추출한 토큰을 콜백으로 흘린다.
     * 정상 종료(done 이벤트 또는 스트림 EOF) 시 {@link ChatStreamListener#onDone(String)},
     * 예외 시 {@link ChatStreamListener#onError(Throwable)}가 정확히 한 번 호출된다.
     */
    void streamChat(ChatbotPayload payload, ChatStreamListener listener);
}
