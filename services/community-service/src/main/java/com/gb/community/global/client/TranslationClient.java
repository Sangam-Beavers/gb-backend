package com.gb.community.global.client;

/**
 * 계정 B 번역 Lambda(Bedrock Claude Haiku)를 호출하는 클라이언트 계약.
 *
 * <p>MSA 경계를 넘는 외부 호출이라 CLAUDE.md §7의 client 패턴을 따른다:
 * 인터페이스를 먼저 정의하고 구현체를 분리한다. 선택 축은 프로파일이 아니라 {@code translation.client}
 * 프로퍼티(dev·stage 모두 bedrock 가능)다. Service는 본 인터페이스에만 의존하므로
 * Mock ↔ Bedrock 전환에 Service 코드는 변경되지 않는다.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link MockTranslationClient} — {@code @ConditionalOnProperty(translation.client=mock, matchIfMissing=true)}.
 *       Bedrock 호출 없이 단순 프리픽스 반환.</li>
 *   <li>{@link BedrockTranslationClient} — {@code @ConditionalOnProperty(translation.client=bedrock)}.
 *       AWS SDK SigV4로 Lambda Function URL POST.</li>
 * </ul>
 *
 * <p>실패 정책 = <b>fail-fast (폴백 없음)</b>: 번역은 표시용이지만 "잘못된 번역"보다 "에러 표시"가 안전하다
 * — 법령·계약 관련 글이 많아 오역의 위험이 크다(MemberClient의 fail-open과 다른 정책). HTTP 5xx·timeout·
 * 호출 실패는 그대로 {@code RuntimeException}을 던지고, Service는 이를 잡지 않아 GlobalExceptionHandler가
 * COMMON5000(500)으로 변환한다. 캐시 INSERT는 정상 응답 시에만 수행한다.
 *
 * <p>Lambda 입출력 계약 SSOT: {@code docs/community/translation.md} §4.
 */
public interface TranslationClient {

    /**
     * 게시글 또는 댓글 본문을 번역한다.
     *
     * @param kind        {@code "post"} 또는 {@code "comment"} — Lambda 측 프롬프트 분기용.
     * @param publicId    본문 식별자(UUID). Lambda 로깅용 메타 — 번역 결과에는 영향 없음.
     * @param title       원문 제목. {@code kind="comment"}이면 null.
     * @param content     원문 본문 (NOT NULL).
     * @param sourceLang  원문 언어 코드 (예: {@code "ko"}). Service가 {@code post.language}/{@code comment.language}에서 가져온다.
     * @param targetLang  대상 언어 코드 ({@code ko}/{@code en}/{@code vi}/{@code fil}). Service에서 화이트리스트 검증 통과 후 전달.
     * @return            번역 결과. {@code translatedContent}는 NOT NULL이며 {@code translatedTitle}은 댓글이면 null.
     */
    TranslationResult translate(
            String kind,
            String publicId,
            String title,
            String content,
            String sourceLang,
            String targetLang);
}
