package com.gb.community.global.client;

/**
 * member-service 응답을 모사한 회원 표시 정보 DTO.
 * MSA 경계를 넘어오는 값이라 {@code userPublicId}(UUID)로만 식별한다.
 *
 * <p>커뮤니티 게시글 카드/상세에 필요한 최소 항목만 담는다:
 * <ul>
 *   <li>{@code nickname} — 작성자 닉네임(author_nickname)</li>
 *   <li>{@code isVerified} — 신분증 인증 배지 여부(author_is_verified, requirements §4)</li>
 *   <li>{@code profileImageUrl} — 작성자 프로필 사진 URL(author_profile_image_url). 미설정 시 null.
 *       member-service가 이미지 도메인 미구현이라 현재는 항상 null이지만, 구현되면 별도 코드 변경 없이
 *       게시글/댓글 응답까지 자동 전달된다(마이페이지와 동일 소스로 연동).</li>
 * </ul>
 */
public record MemberInfo(
        String nickname,
        boolean isVerified,
        String profileImageUrl) {

    /**
     * 프로필 사진 미지정(2-arg) 편의 생성자 — {@code profileImageUrl}을 null로 채운다.
     * 폴백("Unknown")·dev fixture·기존 테스트 등 사진이 무의미한 호출부의 호환을 위해 남겨둔다.
     */
    public MemberInfo(String nickname, boolean isVerified) {
        this(nickname, isVerified, null);
    }
}