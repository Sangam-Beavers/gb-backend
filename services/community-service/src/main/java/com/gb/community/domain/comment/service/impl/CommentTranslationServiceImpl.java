package com.gb.community.domain.comment.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.dto.response.CommentTranslationResponse;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.entity.CommentTranslation;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.comment.repository.CommentTranslationRepository;
import com.gb.community.domain.comment.service.CommentTranslationService;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.global.client.TranslationClient;
import com.gb.community.global.client.TranslationResult;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 번역 서비스 구현.
 *
 * <p>게시글 번역({@code PostTranslationServiceImpl})과 동일 패턴이며, 댓글에는 작성 언어 컬럼이 없어
 * 같은-언어 판정 시 게시글의 {@code language}를 사용한다(댓글은 게시글 컨텍스트에 종속된 본문 — 부모 글의
 * 작성 언어와 같은 언어로 단 댓글이라고 가정).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommentTranslationServiceImpl implements CommentTranslationService {

    /** Post 번역과 동일한 화이트리스트(SSOT는 한 곳이 좋으나 도메인이 다르므로 중복 정의 — 향후 enum 승격 가능). */
    static final Set<String> SUPPORTED_LANGUAGES = Set.of("ko", "en", "vi", "fil");
    static final int MAX_TRANSLATABLE_CONTENT_LENGTH = 5000;

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final CommentTranslationRepository commentTranslationRepository;
    private final TranslationClient translationClient;

    @Override
    public CommentTranslationResponse getOrTranslate(String postPublicId, String commentPublicId, String targetLang) {
        // (1) 활성 게시글 검증 — 없거나 삭제됐으면 COMMUNITY4001. (댓글 검증보다 먼저 — 상위 자원 우선)
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        // (2) 활성 댓글 조회 — 없거나 삭제됐으면 COMMUNITY4002.
        Comment comment = commentRepository.findByPublicIdAndDeletedAtIsNull(commentPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        // (3) URL postId와 댓글의 실제 post 일치 검증 — 불일치는 권한 문제 아닌 "이 게시글에 그런 댓글 없음"
        // (api-spec §7-2와 동일 통합 처리). post는 LAZY 매핑이지만 id 접근은 proxy 초기화 없이 가능.
        if (!comment.getPost().getId().equals(post.getId())) {
            throw new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND);
        }

        // (4) 화이트리스트.
        String normalizedLang = normalizeLanguage(targetLang);
        if (!SUPPORTED_LANGUAGES.contains(normalizedLang)) {
            throw new BusinessException(CommunityErrorCode.UNSUPPORTED_LANGUAGE);
        }

        // (5) 본문 5000자 캡.
        if (comment.getContent() != null && comment.getContent().length() > MAX_TRANSLATABLE_CONTENT_LENGTH) {
            throw new BusinessException(CommunityErrorCode.CONTENT_TOO_LONG);
        }

        // (6) 같은 언어 — 원문 그대로. 댓글에는 language 컬럼이 없어 부모 게시글 언어를 기준으로 한다.
        String commentLanguage = post.getLanguage();
        if (normalizedLang.equals(commentLanguage)) {
            return CommentTranslationResponse.fromOriginal(comment, normalizedLang);
        }

        // (7) 캐시 hit.
        return commentTranslationRepository.findByCommentIdAndLanguage(comment.getId(), normalizedLang)
                .map(CommentTranslationResponse::fromCache)
                // (8) 캐시 미스 — Bedrock 호출 후 INSERT.
                .orElseGet(() -> translateAndCache(comment, commentLanguage, normalizedLang));
    }

    private CommentTranslationResponse translateAndCache(Comment comment, String sourceLang, String targetLang) {
        TranslationResult result = translationClient.translate(
                "comment",
                comment.getPublicId(),
                null,                       // 댓글은 제목 없음 — Lambda 계약상 null
                comment.getContent(),
                sourceLang,
                targetLang);

        CommentTranslation saved = commentTranslationRepository.save(
                CommentTranslation.of(comment, targetLang, result.translatedContent()));
        return CommentTranslationResponse.fromCache(saved);
    }

    private static String normalizeLanguage(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase();
    }
}
