package com.gb.community.domain.comment.dto.response;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.entity.CommentTranslation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 댓글 번역 응답 (api-spec §7-3 — 댓글 번역 보기).
 *
 * <p>댓글은 제목이 없어 {@code translated_content} + {@code translated_language}만 노출한다.
 * {@link com.gb.community.domain.post.dto.response.PostTranslationResponse}와 동일 패턴.
 */
@Getter
public class CommentTranslationResponse {

    @Schema(description = "번역된 댓글 본문 (target_lang)",
            example = "Tôi cũng từng trải qua chuyện tương tự năm ngoái...")
    private final String translatedContent;

    @Schema(description = "응답 언어 코드 (ko/en/vi/fil)", example = "vi",
            allowableValues = {"ko", "en", "vi", "fil"})
    private final String translatedLanguage;

    @Builder
    private CommentTranslationResponse(String translatedContent, String translatedLanguage) {
        this.translatedContent = translatedContent;
        this.translatedLanguage = translatedLanguage;
    }

    /** 같은 언어 요청 — 원문 그대로 응답 (Bedrock 호출·캐시 INSERT 없음). */
    public static CommentTranslationResponse fromOriginal(Comment comment, String targetLang) {
        return CommentTranslationResponse.builder()
                .translatedContent(comment.getContent())
                .translatedLanguage(targetLang)
                .build();
    }

    /** 캐시 hit 또는 방금 INSERT된 번역 행으로부터 응답 조립. */
    public static CommentTranslationResponse fromCache(CommentTranslation translation) {
        return CommentTranslationResponse.builder()
                .translatedContent(translation.getTranslatedContent())
                .translatedLanguage(translation.getLanguage())
                .build();
    }
}
