package com.gb.wallet.global.client;

/**
 * member-service 응답을 모사한 회원 정보 DTO.
 * MSA 경계를 넘어오는 값이라 {@code userPublicId}(UUID)로만 식별한다.
 *
 * <p>{@code email}은 앱 사용자 검증(validate-member)용으로 추가했다. fallback {@link MemberInfo}처럼
 * 이메일을 알 수 없는 경우 null이 들어갈 수 있다.
 */
public record MemberInfo(
        String userPublicId,
        String email,
        String nickname,
        String nationality,
        boolean isVerified) {
}
