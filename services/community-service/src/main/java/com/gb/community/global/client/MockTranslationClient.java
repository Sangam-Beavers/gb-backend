package com.gb.community.global.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock {@link TranslationClient} — Bedrock 호출 없이 프리픽스 응답.
 *
 * <p>활성화 조건: {@code translation.client=mock} 또는 미설정(기본값 mock).
 * 즉 yml에 명시 안 하면 Mock이 활성. dev에서 진짜 Bedrock을 쓰려면
 * {@code application-dev.yml}에 {@code translation.client: bedrock}을 명시한다.
 *
 * <p>로컬에서는 계정 B Lambda·Bedrock 자격증명 없이도 번역 API 응답 형태를 그대로 검증할 수 있다.
 * "[VI] 원문" 형식으로 대문자 언어 코드 프리픽스를 붙이고, 같은 언어 요청 시에도 결과를 동일 형태로 반환한다
 * (Service에서 같은 언어 분기를 먼저 처리하므로 본 구현이 실제로 같은 언어로 호출될 일은 없지만,
 * 단위 테스트가 직접 호출하는 시나리오를 위해 안전한 형태 유지).
 *
 * <p>{@code modelId}는 {@code "mock"}, 토큰은 0으로 설정한다 — 운영 빈과 구분되도록.
 */
@Component
@ConditionalOnProperty(name = "translation.client", havingValue = "mock", matchIfMissing = true)
public class MockTranslationClient implements TranslationClient {

    private static final String MODEL_ID = "mock";

    @Override
    public TranslationResult translate(
            String kind,
            String publicId,
            String title,
            String content,
            String sourceLang,
            String targetLang) {
        // title은 post일 때만 채우고, comment는 null로 둔다(Lambda 응답 계약과 동일).
        String translatedTitle = title != null ? prefix(targetLang, title) : null;
        String translatedContent = prefix(targetLang, content);
        return new TranslationResult(
                translatedTitle,
                translatedContent,
                targetLang,
                MODEL_ID,
                0,
                0);
    }

    /**
     * "[VI] 원문" 형식 — 언어 코드 대문자 프리픽스. UI 검증 시 어떤 언어로 번역됐는지 한눈에 보이게.
     * targetLang가 null이면(비정상 입력) 빈 대괄호로 두지 않고 원본 그대로 둔다 — Service 검증을 통과한
     * 호출만 들어오므로 실무상 발생하지 않는 경로의 방어.
     */
    private static String prefix(String targetLang, String text) {
        if (targetLang == null || targetLang.isBlank()) {
            return text;
        }
        return "[" + targetLang.toUpperCase() + "] " + text;
    }
}
