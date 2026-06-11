package com.gb.member.domain.member.entity;

import java.util.Optional;

/**
 * 회원 연령대. 가입 시 수집한다(이슈 #203). 10년 단위 구간으로 보관한다(생년월일 같은 정밀 PII는 받지 않음).
 *
 * <ul>
 *   <li>{@link #TEENS} — 10대(10대 이하 포함)</li>
 *   <li>{@link #TWENTIES} — 20대</li>
 *   <li>{@link #THIRTIES} — 30대</li>
 *   <li>{@link #FORTIES} — 40대</li>
 *   <li>{@link #FIFTIES} — 50대</li>
 *   <li>{@link #SIXTIES_PLUS} — 60대 이상</li>
 * </ul>
 *
 * <p>JSON 직렬화 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(CLAUDE.md §5).
 * 외부 입력(요청 문자열)은 {@link #fromCode}로 안전하게 변환하고(예외 대신 Optional),
 * 변환 실패 시 호출 측(Service)이 도메인 ErrorCode로 처리한다(conventions §6 — @Pattern 미사용).
 */
public enum AgeRange {
    TEENS,
    TWENTIES,
    THIRTIES,
    FORTIES,
    FIFTIES,
    SIXTIES_PLUS;

    /** 요청 문자열을 enum으로 안전 변환한다. null·미정의 값은 {@link Optional#empty()}. */
    public static Optional<AgeRange> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
