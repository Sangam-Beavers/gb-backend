package com.gb.wallet.global.client;

/**
 * member-service에서 회원 정보를 가져오는 클라이언트 계약.
 * MSA 경계를 넘어가는 호출이라 식별자는 user_public_id(UUID)만 사용한다.
 *
 * <p>TODO: member-service 구현 후 실제 HTTP/feign 기반 RealMemberClient(@Profile prod)로 교체.
 *       현재는 개발용 {@link MockMemberClient}(@Profile("dev"))만 존재.
 */
public interface MemberClient {

    /**
     * 회원 정보 조회. 미존재 시 구현체가 fallback {@link MemberInfo}를 반환할 수 있다
     * (호출 측 null 검사 부담을 덜기 위함).
     */
    MemberInfo getMember(String userPublicId);
}
