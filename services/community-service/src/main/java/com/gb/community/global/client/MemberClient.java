package com.gb.community.global.client;

import java.util.Collection;
import java.util.Map;

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

    /**
     * 회원 표시 정보를 여러 건 한 번에 조회한다(배치). 게시글/관심글/댓글 목록의 작성자 조회 N+1을 1회 호출로 줄인다.
     *
     * <p>반환 맵은 <b>요청한 모든 id를 키로 포함</b>한다 — 미존재 id는 {@link #getMember}와 동일하게
     * fallback("Unknown")으로 채워, 호출 측의 {@code map.get(id)} null 검사 부담을 없앤다(표시용 보조 조회라 fail-fast 불필요).
     *
     * <p>TODO: RealMemberClient(@Profile("!dev"))는 member-service 배치 조회 API(예: {@code GET /members?ids=...}) 1회 호출로 구현한다.
     *       그 API가 일부만 응답하더라도 위 "모든 요청 id 포함(누락=fallback)" 계약을 지켜야 한다.
     */
    Map<String, MemberInfo> getMembers(Collection<String> userPublicIds);
}