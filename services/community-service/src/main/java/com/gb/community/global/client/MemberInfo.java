package com.gb.community.global.client;

/**
 * member-service 응답을 모사한 회원 표시 정보 DTO.
 * MSA 경계를 넘어오는 값이라 {@code userPublicId}(UUID)로만 식별한다.
 *
 * <p>커뮤니티 게시글 카드/상세에 필요한 최소 항목만 담는다:
 * <ul>
 *   <li>{@code nickname} — 작성자 닉네임(author_nickname)</li>
 *   <li>{@code isVerified} — 신분증 인증 배지 여부(author_is_verified, requirements §4)</li>
 * </ul>
 */
public record MemberInfo(
        String nickname,
        boolean isVerified) {
}