package com.gb.wallet.global.client;

import java.util.Optional;

/**
 * member-service에서 회원 정보를 가져오는 클라이언트 계약.
 * MSA 경계를 넘어가는 호출이라 식별자는 user_public_id(UUID) 또는 이메일을 사용한다.
 *
 * <p>TODO: member-service 구현 후 실제 HTTP/feign 기반 RealMemberClient(@Profile prod)로 교체.
 *       현재는 개발용 {@link MockMemberClient}(@Profile({"dev", "stage"}))만 존재.
 */
public interface MemberClient {

    /**
     * 회원 정보 조회. 미존재 시 구현체가 fallback {@link MemberInfo}를 반환할 수 있다
     * (호출 측 null 검사 부담을 덜기 위함 — 예: 최근 송금자 목록 매핑).
     */
    MemberInfo getMember(String userPublicId);

    /**
     * 이메일로 회원 정보 조회. 미존재 시 {@link Optional#empty()}.
     *
     * <p>{@link #getMember}와 달리 fallback을 쓰지 않는 이유: 이 메서드는 "앱 사용자 유효성 검증"
     * 용도라 "없으면 없다"고 명확히 알려야 호출 측에서 MEMBER_NOT_FOUND로 변환할 수 있다.
     */
    Optional<MemberInfo> findByEmail(String email);
}
