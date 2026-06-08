package com.gb.community.domain.comment.service;

import com.gb.community.domain.comment.dto.response.CommentTranslationResponse;

/**
 * 댓글 번역 보기 — Bedrock Claude Haiku 호출 + 캐시 hit/miss.
 *
 * <p>흐름 (api-spec §7-3):
 * <ol>
 *   <li>활성 게시글 조회 (없으면 COMMUNITY4001).</li>
 *   <li>활성 댓글 조회 (없으면 COMMUNITY4002).</li>
 *   <li>URL postId와 댓글의 실제 post 일치 검증 (불일치 → COMMUNITY4002, §7-2와 동일 통합).</li>
 *   <li>language 화이트리스트 검증 (ko/en/vi/fil 외 → COMMUNITY4003).</li>
 *   <li>본문 5000자 캡 검증 (초과 → COMMUNITY4004).</li>
 *   <li>{@code post.language == targetLang}이면 원문 그대로 반환 (댓글에는 language 컬럼이 없어 게시글 언어를 따른다).</li>
 *   <li>{@code comment_translations} 캐시 hit이면 즉시 반환.</li>
 *   <li>캐시 미스면 {@code TranslationClient.translate(...)} → 결과 INSERT 후 반환.</li>
 * </ol>
 */
public interface CommentTranslationService {

    /**
     * @param postPublicId    게시글 UUID (URL path) — 상위 자원 검증용
     * @param commentPublicId 댓글 UUID (URL path)
     * @param targetLang      요청 대상 언어 (URL query)
     */
    CommentTranslationResponse getOrTranslate(String postPublicId, String commentPublicId, String targetLang);
}
