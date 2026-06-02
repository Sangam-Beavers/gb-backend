package com.gb.community.global.client;

/**
 * member-service에서 회원 표시 정보를 가져오는 클라이언트 계약.
 * MSA 경계를 넘어가는 호출이라 식별자는 {@code user_public_id}(UUID)를 사용한다
 * (CLAUDE.md §7 — 경계 넘는 회원 참조는 public_id, 물리 FK·DB 직접 SELECT 금지).
 *
 * <p>커뮤니티 게시글 응답에 작성자 닉네임/인증배지를 실어야 하므로 조회만 필요하다.
 * wallet-service의 {@code MemberClient}와 달리 이메일 조회는 쓰지 않아 {@link #getMember}만 둔다.
 *
 * <p>TODO: member-service 구현 후 실제 HTTP 기반 RealMemberClient(@Profile("!dev"))로 교체.
 *       현재는 개발용 {@link MockMemberClient}(@Profile("dev"))만 존재.
 */
public interface MemberClient {

    /**
     * 회원 표시 정보 조회. 미존재 시 구현체가 fallback {@link MemberInfo}("Unknown")를 반환한다
     * (게시글 목록 매핑 시 호출 측 null 검사 부담을 덜기 위함 — 표시용 보조 조회라 fail-fast 불필요).
     */
    MemberInfo getMember(String userPublicId);
}