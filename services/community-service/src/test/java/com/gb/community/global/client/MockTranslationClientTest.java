package com.gb.community.global.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MockTranslationClient} 동작 검증 — dev/test에서 Bedrock 없이도 응답 형태가 일관되는지 확인.
 *
 * <p>"[LANG] 원문" 프리픽스 / 댓글 = title null / modelId="mock" / tokens=0 계약을 1차 가드한다.
 */
class MockTranslationClientTest {

    private final MockTranslationClient client = new MockTranslationClient();

    @Test
    @DisplayName("post 번역: title/content에 [VI] 프리픽스 + target_lang echo + modelId=mock")
    void translate_post_프리픽스() {
        TranslationResult result = client.translate(
                "post", "pid-1", "최저임금", "시급이 낮아요", "ko", "vi");

        assertThat(result.translatedTitle()).isEqualTo("[VI] 최저임금");
        assertThat(result.translatedContent()).isEqualTo("[VI] 시급이 낮아요");
        assertThat(result.targetLang()).isEqualTo("vi");
        assertThat(result.modelId()).isEqualTo("mock");
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    @DisplayName("comment 번역: title=null이면 응답 title도 null (Lambda 계약 일치)")
    void translate_comment_title_null() {
        TranslationResult result = client.translate(
                "comment", "cid-1", null, "댓글 본문", "ko", "en");

        assertThat(result.translatedTitle()).isNull();
        assertThat(result.translatedContent()).isEqualTo("[EN] 댓글 본문");
        assertThat(result.targetLang()).isEqualTo("en");
    }

    @Test
    @DisplayName("같은 언어 호출 시에도 프리픽스 응답 — Service가 같은 언어 분기를 먼저 처리하지만 직접 호출 시 안전 형태")
    void translate_같은_언어() {
        TranslationResult result = client.translate(
                "post", "pid", "제목", "본문", "ko", "ko");

        assertThat(result.translatedContent()).isEqualTo("[KO] 본문");
    }

    @Test
    @DisplayName("targetLang이 비어 있으면(비정상 입력) 원본 그대로 — 방어 가드")
    void translate_targetLang_blank() {
        TranslationResult result = client.translate(
                "post", "pid", "제목", "본문", "ko", "");

        assertThat(result.translatedContent()).isEqualTo("본문");
    }
}
