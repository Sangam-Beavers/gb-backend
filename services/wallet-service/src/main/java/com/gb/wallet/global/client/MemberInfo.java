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
 *
 * <p>{@code trustGrade}는 마일스톤 기반 신뢰등급(이슈 #193 — member display-info의 {@code trust_grade}
 * 모사, Phase 1: NEWCOMER/VERIFIED). wallet은 아직 소비하지 않아 <b>필드만 선재</b>한다 — 종전 6-arg
 * 생성자는 기본값(NEWCOMER)으로 채워 기존 호출부와 컴파일 호환을 유지하며, recent-recipients 등
 * 응답 노출은 Phase 3 범위라 본 이슈에서 변경하지 않는다.
 */
public record MemberInfo(
        String userPublicId,
        String email,
        String name,
        String nickname,
        String nationality,
        boolean isVerified,
        String trustGrade) {

    /** Phase 1 기본 신뢰등급 — trust_grade 미지정(종전 시그니처)·폴백 시 기본값. */
    public static final String DEFAULT_TRUST_GRADE = "NEWCOMER";

    /** 종전 6-arg 시그니처 호환 생성자 — {@code trustGrade}를 기본값(NEWCOMER)으로 채운다. */
    public MemberInfo(String userPublicId, String email, String name,
                      String nickname, String nationality, boolean isVerified) {
        this(userPublicId, email, name, nickname, nationality, isVerified, DEFAULT_TRUST_GRADE);
    }
}
