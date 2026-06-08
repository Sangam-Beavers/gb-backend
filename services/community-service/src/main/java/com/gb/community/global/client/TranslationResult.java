package com.gb.community.global.client;

/**
 * 번역 Lambda 응답을 모사한 결과 DTO.
 *
 * <p>Lambda 계약(docs/community/translation.md §4)의 응답 필드를 매핑한다:
 * <ul>
 *   <li>{@code translatedTitle} — {@code kind="post"}일 때만 채워지고, {@code kind="comment"}는 null.</li>
 *   <li>{@code translatedContent} — 번역 본문(NOT NULL — Lambda가 실패 시 5xx로 응답).</li>
 *   <li>{@code targetLang} — 백엔드 요청을 echo (검증용, 응답 직렬화에 그대로 사용).</li>
 *   <li>{@code modelId} — 운영 로그/비용 분석용 (응답에 노출하지 않음 — 인프라 내부 정보).</li>
 *   <li>{@code inputTokens}/{@code outputTokens} — 운영 모니터링용 (응답 비노출).</li>
 * </ul>
 *
 * <p>record 채택: 본 DTO는 불변이며 식별 동등성 불필요. {@link MemberInfo}와 동일 패턴.
 */
public record TranslationResult(
        String translatedTitle,
        String translatedContent,
        String targetLang,
        String modelId,
        int inputTokens,
        int outputTokens) {
}
