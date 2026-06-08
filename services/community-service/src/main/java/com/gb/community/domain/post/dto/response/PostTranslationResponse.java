package com.gb.community.domain.post.dto.response;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostTranslation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 게시글 번역 응답 (api-spec §3 — 게시글 번역 보기).
 *
 * <p>JSON 필드명은 전역 SNAKE_CASE 설정으로 변환된다 ({@code translated_title}/{@code translated_content}/{@code translated_language}).
 *
 * <p>세 가지 생성 경로:
 * <ol>
 *   <li>{@link #fromOriginal(Post, String)} — {@code post.language == target_lang} 시 원문 그대로 반환.
 *       Bedrock 호출도, 캐시 INSERT도 일어나지 않는다.</li>
 *   <li>{@link #fromCache(PostTranslation)} — 캐시 hit. 저장된 번역 그대로 반환.</li>
 *   <li>{@link #fromCache(PostTranslation)} (캐시 미스 시 INSERT 후 호출) — 정상 번역 결과.</li>
 * </ol>
 *
 * <p>{@code translated_language}는 실제 응답 언어 코드 — 같은 언어 경로는 원문 언어가, 캐시/번역 경로는
 * 요청 {@code target_lang}이 들어간다. 프론트가 UI 토글 상태와 일치 확인에 사용한다.
 */
@Getter
public class PostTranslationResponse {

    @Schema(description = "번역된 제목 (target_lang)", example = "Lương dưới mức tối thiểu phải không?")
    private final String translatedTitle;

    @Schema(description = "번역된 본문 (target_lang)",
            example = "Tiền lương theo giờ là 9000 won, nhưng...")
    private final String translatedContent;

    @Schema(description = "응답 언어 코드 (ko/en/vi/fil)", example = "vi",
            allowableValues = {"ko", "en", "vi", "fil"})
    private final String translatedLanguage;

    @Builder
    private PostTranslationResponse(String translatedTitle, String translatedContent, String translatedLanguage) {
        this.translatedTitle = translatedTitle;
        this.translatedContent = translatedContent;
        this.translatedLanguage = translatedLanguage;
    }

    /**
     * 같은 언어 요청 처리 — 원문 그대로 응답에 담는다 (Bedrock 호출·캐시 INSERT 없음).
     * 사용 위치: Service가 {@code post.language == targetLang}을 감지한 경로.
     */
    public static PostTranslationResponse fromOriginal(Post post, String targetLang) {
        return PostTranslationResponse.builder()
                .translatedTitle(post.getTitle())
                .translatedContent(post.getContent())
                .translatedLanguage(targetLang)
                .build();
    }

    /**
     * 캐시 hit 또는 방금 INSERT된 번역 행으로부터 응답 조립.
     */
    public static PostTranslationResponse fromCache(PostTranslation translation) {
        return PostTranslationResponse.builder()
                .translatedTitle(translation.getTranslatedTitle())
                .translatedContent(translation.getTranslatedContent())
                .translatedLanguage(translation.getLanguage())
                .build();
    }
}
