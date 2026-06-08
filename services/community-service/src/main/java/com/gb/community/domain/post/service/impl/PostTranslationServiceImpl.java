package com.gb.community.domain.post.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.post.dto.response.PostTranslationResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostTranslation;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.repository.PostTranslationRepository;
import com.gb.community.domain.post.service.PostTranslationService;
import com.gb.community.global.client.TranslationClient;
import com.gb.community.global.client.TranslationResult;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시글 번역 서비스 구현 — Bedrock Lambda 호출 + 캐시 hit/miss.
 *
 * <p>인터페이스 javadoc({@link PostTranslationService})에서 흐름·예외 매핑 SSOT.
 *
 * <p>트랜잭션 정책: 메서드 전체 {@code @Transactional}(readOnly=false). 캐시 hit/일치 언어/원문 그대로 경로는
 * SELECT만 발생하지만, 캐시 미스 경로는 외부 호출(Bedrock Lambda) + INSERT가 묶인다. 외부 호출이
 * 트랜잭션 안에 있는 것은 보통 안 좋은 패턴(커넥션 점유)이지만, 본 흐름은:
 * <ul>
 *   <li>경로가 짧고(단일 SELECT → 외부 호출 → 단일 INSERT) 행 락이 발생하지 않는다.</li>
 *   <li>본문(post)을 다시 읽거나 갱신하지 않으므로 외부 호출 동안 잠그는 게 없다.</li>
 *   <li>같은 글에 동시 번역 요청이 들어와도 PK {@code (post_id, language)} 중복 INSERT는 UNIQUE 위반으로
 *       자연스럽게 잡힌다(드물어 트랜잭션 retry 없이 통과 또는 422).</li>
 * </ul>
 * 따라서 단일 트랜잭션으로 단순 처리한다. 같은 글에 한 사용자가 연달아 번역 요청하는 빈도가 낮아
 * 동시 INSERT 충돌은 운영상 무시 가능.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PostTranslationServiceImpl implements PostTranslationService {

    /** 번역 화이트리스트 (CLAUDE.md §6 — 번역은 4개 언어로 제한해 비용·품질 보장). */
    static final Set<String> SUPPORTED_LANGUAGES = Set.of("ko", "en", "vi", "fil");

    /** 번역 본문 길이 캡 — Bedrock 단발 호출 비용·지연 보호. 작성 상한(10000자)보다 짧다. */
    static final int MAX_TRANSLATABLE_CONTENT_LENGTH = 5000;

    private final PostRepository postRepository;
    private final PostTranslationRepository postTranslationRepository;
    private final TranslationClient translationClient;

    @Override
    public PostTranslationResponse getOrTranslate(String postPublicId, String targetLang) {
        // (1) 활성 게시글 조회 — 없거나 삭제됐으면 COMMUNITY4001.
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        // (2) 화이트리스트 검증 — 외 → COMMUNITY4003. null/blank는 Controller에서 @NotBlank로 차단되지만
        // 방어적으로 한 번 더(서비스 단독 호출/내부 사용 대비).
        String normalizedLang = normalizeLanguage(targetLang);
        if (!SUPPORTED_LANGUAGES.contains(normalizedLang)) {
            throw new BusinessException(CommunityErrorCode.UNSUPPORTED_LANGUAGE);
        }

        // (3) 본문 5000자 캡 — 번역 비용·지연 가드. Bedrock 호출 전이라 캐시 hit/같은 언어도 차단된다 —
        // 이미 캐시된 긴 글의 다른 언어 요청도 막힌다(정책 일관: 긴 글은 번역 미지원).
        if (post.getContent() != null && post.getContent().length() > MAX_TRANSLATABLE_CONTENT_LENGTH) {
            throw new BusinessException(CommunityErrorCode.CONTENT_TOO_LONG);
        }

        // (4) 같은 언어 요청 — Bedrock 호출 없이 원문 그대로. 캐시 INSERT도 안 함(원문은 본문에 있음).
        if (normalizedLang.equals(post.getLanguage())) {
            return PostTranslationResponse.fromOriginal(post, normalizedLang);
        }

        // (5) 캐시 hit — 즉시 반환.
        return postTranslationRepository.findByPostIdAndLanguage(post.getId(), normalizedLang)
                .map(PostTranslationResponse::fromCache)
                // (6) 캐시 미스 — Bedrock Lambda 호출 후 INSERT.
                .orElseGet(() -> translateAndCache(post, normalizedLang));
    }

    private PostTranslationResponse translateAndCache(Post post, String targetLang) {
        // Bedrock 호출 — 실패 시 RuntimeException이 그대로 올라가 GlobalExceptionHandler가 COMMON5000으로
        // 변환한다(fail-fast 정책 — translation.md §4). 캐시 INSERT는 정상 응답 시에만.
        TranslationResult result = translationClient.translate(
                "post",
                post.getPublicId(),
                post.getTitle(),
                post.getContent(),
                post.getLanguage(),
                targetLang);

        // result.translatedContent/Title은 NOT NULL 계약(Lambda 측이 보장).
        PostTranslation saved = postTranslationRepository.save(
                PostTranslation.of(post, targetLang, result.translatedTitle(), result.translatedContent()));
        return PostTranslationResponse.fromCache(saved);
    }

    /** null/공백/대소문자 차이를 정규화 — null이면 그대로 null 반환해 화이트리스트에서 탈락하도록. */
    private static String normalizeLanguage(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase();
    }
}
