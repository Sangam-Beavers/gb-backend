package com.gb.wallet.global.client;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * member-service에서 회원 정보를 가져오는 클라이언트 계약.
 * MSA 경계를 넘어가는 호출이라 식별자는 user_public_id(UUID) 또는 이메일을 사용한다.
 *
 * <p>구현체(프로파일별 정확히 1개 — member-service 표시정보 API(auth §13)를 호출):
 * <ul>
 *   <li>{@link RealMemberClient} — {@code @Profile("!dev & !test")}(stage·prod). HTTP + JWT 릴레이.
 *       getMember=fail-open("Unknown" 폴백) / findByEmail=fail-fast(404 MEMBER4001→empty, 그 외 COMMON5000).</li>
 *   <li>{@link DevMemberClient} — {@code @Profile("dev")}. fixture 5명 우선, 미스만 Real 로직에 위임.</li>
 *   <li>test — 빈 없음. 단위는 {@code @Mock}, 컨텍스트 로딩은 {@code @MockitoBean}.</li>
 * </ul>
 */
public interface MemberClient {

    /**
     * 회원 정보 조회. 미존재 시 구현체가 fallback {@link MemberInfo}를 반환할 수 있다
     * (호출 측 null 검사 부담을 덜기 위함 — 예: 확인증 본명 채움).
     */
    MemberInfo getMember(String userPublicId);

    /**
     * 회원 표시 정보를 여러 건 한 번에 조회한다(배치). 최근 송금 수신자 목록(≤10명)의 건별 호출 N+1을
     * 1회 호출로 줄인다 — community {@code MemberClient.getMembers}와 동일 계약.
     *
     * <p>반환 맵은 <b>요청한 모든 id를 키로 포함</b>한다 — 미존재·장애분은 {@link #getMember}와 동일한
     * fallback("Unknown")으로 채워, 호출 측의 {@code map.get(id)} null 검사 부담을 없앤다(표시용 fail-open).
     */
    Map<String, MemberInfo> getMembers(Collection<String> userPublicIds);

    /**
     * 이메일로 회원 정보 조회. 미존재 시 {@link Optional#empty()}.
     *
     * <p>{@link #getMember}와 달리 fallback을 쓰지 않는 이유: 이 메서드는 "앱 사용자 유효성 검증"
     * 용도라 "없으면 없다"고 명확히 알려야 호출 측에서 MEMBER_NOT_FOUND로 변환할 수 있다.
     */
    Optional<MemberInfo> findByEmail(String email);
}
