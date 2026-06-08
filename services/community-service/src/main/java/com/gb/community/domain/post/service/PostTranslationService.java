package com.gb.community.domain.post.service;

import com.gb.community.domain.post.dto.response.PostTranslationResponse;

/**
 * 게시글 번역 보기 — Bedrock Claude Haiku 호출 + 캐시 hit/miss 처리.
 *
 * <p>흐름 (api-spec §3, requirements §6, translation.md):
 * <ol>
 *   <li>활성 게시글 조회 (없으면 COMMUNITY4001).</li>
 *   <li>language 화이트리스트(ko/en/vi/fil) 검증 (외 → COMMUNITY4003).</li>
 *   <li>본문 5000자 캡 검증 (초과 → COMMUNITY4004).</li>
 *   <li>{@code post.language == targetLang}면 원문 그대로 반환 (Bedrock 호출 X).</li>
 *   <li>{@code post_translations} 캐시 hit이면 즉시 반환.</li>
 *   <li>캐시 미스면 {@code TranslationClient.translate(...)} → 결과 INSERT 후 반환.</li>
 * </ol>
 *
 * <p>실패는 모두 {@code BusinessException}으로만 던진다(CLAUDE.md §6) — Bedrock 5xx/timeout은
 * {@code TranslationClient}가 {@code RuntimeException}을 던지고, Service는 잡지 않아 핸들러가 COMMON5000으로 변환.
 */
public interface PostTranslationService {

    /**
     * @param postPublicId 게시글 UUID (URL path)
     * @param targetLang   요청 대상 언어 (URL query — Controller에서 nullable·blank 검증 후 전달)
     */
    PostTranslationResponse getOrTranslate(String postPublicId, String targetLang);
}
