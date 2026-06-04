package com.gb.document.domain.chat.util;

import com.gb.document.domain.chat.service.ChatStreamListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * {@link ChatStreamListener}를 {@link SseEmitter}에 어댑팅. Lambda에서 흘러오는 토큰을
 * {@code event: token}, 종료 신호를 {@code event: done}으로 SSE 스트림에 그대로 중계한다.
 *
 * <p>{@link com.gb.document.domain.chat.controller.ChatController}에서 사용된다.
 */
@Slf4j
@RequiredArgsConstructor
public final class SseRelayListener implements ChatStreamListener {

    private final SseEmitter emitter;

    @Override
    public void onToken(String text) {
        try {
            emitter.send(SseEmitter.event().name("token").data(text));
        } catch (IOException e) {
            // 클라이언트가 끊은 경우 등. 더 이상 보낼 수 없으니 에러로 종료.
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
        log.warn("Chat stream error", t);
        emitter.completeWithError(t);
    }
}
