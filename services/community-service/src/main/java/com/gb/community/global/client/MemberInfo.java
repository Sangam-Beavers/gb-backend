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
 *   <li>{@code trustGrade} — 마일스톤 기반 신뢰등급(author_trust_grade, 이슈 #194). member-service
 *       display-info의 {@code trust_grade}를 모사한다. Phase 1은 NEWCOMER/VERIFIED 2단계. 표시용
 *       보조 데이터라 응답 누락(null)·폴백 시 {@link #DEFAULT_TRUST_GRADE}(NEWCOMER)로 fail-open.</li>
 *   <li>{@code avatarHue} — 프로필 아바타 색조 회전 각도(author_avatar_hue, 0~359°). member-service
 *       display-info의 {@code avatar_hue}를 모사한다. 마이페이지 "색깔 변경"으로 저장한 값으로,
 *       게시글/댓글 작성자 아바타 색을 마이페이지와 동일하게 표시한다. 응답 누락·폴백 시 0(색 회전 없음).</li>
 * </ul>
 */
public record MemberInfo(
        String nickname,
        boolean isVerified,
        String profileImageUrl,
        String trustGrade,
        int avatarHue) {

    /** Phase 1 기본 신뢰등급. trust_grade 미지정·응답 누락·폴백("Unknown") 시 표시용 기본값(fail-open). */
    public static final String DEFAULT_TRUST_GRADE = "NEWCOMER";

    /**
     * 프로필 사진·신뢰등급 미지정(2-arg) 편의 생성자 — {@code profileImageUrl}을 null,
     * {@code trustGrade}를 기본값(NEWCOMER), {@code avatarHue}를 0으로 채운다.
     * 폴백("Unknown")·기존 테스트 등 해당 값이 무의미한 호출부의 호환을 위해 남겨둔다.
     */
    public MemberInfo(String nickname, boolean isVerified) {
        this(nickname, isVerified, null, DEFAULT_TRUST_GRADE, 0);
    }

    /** 신뢰등급·색조 미지정(3-arg) 편의 생성자 — {@code trustGrade}를 기본값, {@code avatarHue}를 0으로 채운다. */
    public MemberInfo(String nickname, boolean isVerified, String profileImageUrl) {
        this(nickname, isVerified, profileImageUrl, DEFAULT_TRUST_GRADE, 0);
    }

    /** 색조 미지정(4-arg) 편의 생성자 — {@code avatarHue}를 0으로 채운다(종전 시그니처 호환 — FIXTURES·테스트). */
    public MemberInfo(String nickname, boolean isVerified, String profileImageUrl, String trustGrade) {
        this(nickname, isVerified, profileImageUrl, trustGrade, 0);
    }
}
