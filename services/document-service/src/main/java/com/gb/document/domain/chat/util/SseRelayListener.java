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
 * <p>{@link com.gb.document.domain.chat.controller.ChatController}에서 SSE 중계에 사용된다.
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
        // 클라이언트에 event:error를 먼저 흘린다 — completeWithError만 하면 (특히 토큰이 이미
        // 나간 뒤엔) 연결이 그냥 끊겨 프론트가 원인 없이 "빈 답변"으로 보게 된다.
        // 상세 사유는 서버 로그에만 남기고, 사용자에겐 일반 안내 문구만 보낸다.
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", "답변 생성 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.")));
        } catch (IOException ignored) {
            // 이미 끊긴 연결 — 보낼 수 없으면 그대로 종료.
        }
        emitter.completeWithError(t);
    }
}
