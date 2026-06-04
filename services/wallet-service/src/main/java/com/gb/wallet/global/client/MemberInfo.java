package com.gb.wallet.global.client;

/**
 * member-service 응답을 모사한 회원 정보 DTO.
 * MSA 경계를 넘어오는 값이라 {@code userPublicId}(UUID)로만 식별한다.
 *
 * <p>{@code email}은 앱 사용자 검증(validate-member)용으로 추가했다. fallback {@link MemberInfo}처럼
 * 이메일을 알 수 없는 경우 null이 들어갈 수 있다.
 *
 * <p>{@code name}은 회원의 본명. 송금 확인증의 {@code receiver_name}/{@code sender_name}에 사용된다
 * (격식 있는 영수증 문서에는 본명이 적합). {@code nickname}은 캐주얼한 거래 목록 등에서 활용.
 * fallback의 경우 두 값이 같은 placeholder("Unknown")로 채워질 수 있다.
 */
public record MemberInfo(
        String userPublicId,
        String email,
        String name,
        String nickname,
        String nationality,
        boolean isVerified) {
}
