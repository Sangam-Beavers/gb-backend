package com.gb.document.domain.chat.service;

/**
 * 챗봇 Lambda 스트리밍 응답을 컨트롤러(SseEmitter)에 중계하기 위한 콜백.
 * 토큰이 도착할 때마다 {@link #onToken}이, 스트림 종료 시 {@link #onDone}이,
 * 예외 발생 시 {@link #onError}가 한 번 호출된다.
 */
public interface ChatStreamListener {

    /** 한 토큰(또는 작은 텍스트 청크)이 도착했을 때. SseEmitter.event().name("token") 으로 중계. */
    void onToken(String text);

    /** Lambda가 done 이벤트로 종료를 알렸을 때. session_id가 포함된다. */
    void onDone(String sessionId);

    /** 네트워크/서명/파싱 등 어떤 단계에서든 예외가 났을 때. */
    void onError(Throwable t);
}
