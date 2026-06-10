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
 * <p>구현체(프로파일별 정확히 1개 — member-service 표시정보 API(auth §13)를 호출):
 * <ul>
 *   <li>{@link RealMemberClient} — {@code @Profile("!dev & !test")}(stage·prod). HTTP + JWT 릴레이,
 *       표시용이라 장애 시 fail-open("Unknown" 폴백).</li>
 *   <li>{@link DevMemberClient} — {@code @Profile("dev")}. 시드 fixture 우선, 미스만 Real 로직에 위임.</li>
 *   <li>test — 빈 없음. 단위는 {@code @Mock}, 컨텍스트 로딩은 {@code @MockitoBean}.</li>
 * </ul>
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
     * {@link RealMemberClient}는 배치 조회 API({@code GET /api/v1/members/display-info?public_ids=...},
     * auth §13-1) 1회 호출로 구현하며, API가 일부만 응답해도(미존재·탈퇴 제외) 이 계약을 지킨다.
     */
    Map<String, MemberInfo> getMembers(Collection<String> userPublicIds);

    /**
     * 커뮤니티 활동 제한 여부 조회. <b>fail-fast</b> — 검증 용도라 HTTP 장애 시 예외를 올린다.
     * member-service {@code GET /api/v1/internal/admin/members/{publicId}/community-status} 호출.
     *
     * <p>member-service 5xx·연결 실패 → {@code COMMON5000}. 회원 없음(404) → 제한 없음(false) 처리
     * (community-service가 게시글 작성 흐름에서 이미 사용자 존재를 전제함).
     */
    boolean isCommunityBanned(String userPublicId);
}