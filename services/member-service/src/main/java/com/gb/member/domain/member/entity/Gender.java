package com.gb.member.domain.member.entity;

import java.util.Optional;

/**
 * 회원 성별. 가입 시 수집한다(이슈 #203). 남/여 2개 값만 둔다.
 *
 * <ul>
 *   <li>{@link #MALE} — 남</li>
 *   <li>{@link #FEMALE} — 여</li>
 * </ul>
 *
 * <p>JSON 직렬화 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(CLAUDE.md §5).
 * 외부 입력(요청 문자열)은 {@link #fromCode}로 안전하게 변환하고(예외 대신 Optional),
 * 변환 실패 시 호출 측(Service)이 도메인 ErrorCode로 처리한다(conventions §6 — @Pattern 미사용).
 */
public enum Gender {
    MALE,
    FEMALE;

    /** 요청 문자열을 enum으로 안전 변환한다. null·미정의 값은 {@link Optional#empty()}. */
    public static Optional<Gender> fromCode(String code) {
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
