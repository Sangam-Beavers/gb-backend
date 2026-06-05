package com.gb.member.global.client;

/**
 * wallet-service에 전자지갑 생성을 위임하는 클라이언트 계약(이슈 #152).
 *
 * <p>신분증 인증(APPROVED) 시점에 사용자당 1개의 지갑을 보장한다. wallet-service의
 * {@code POST /api/v1/wallets}는 멱등이라 같은 사용자로 반복 호출돼도 안전하다.
 *
 * <p>MSA 경계를 넘는 호출이므로 식별자는 {@code user_public_id}(UUID)를 쓴다(CLAUDE §7).
 * Service는 이 인터페이스에만 의존하므로 Mock(dev/test) ↔ Real(prod/stage) 교체 시
 * Service 코드는 바뀌지 않는다.
 *
 * <p>구현체는 호출 실패를 그대로 던지지 않고 자기 책임 안에서 매핑한다(상세는 구현체 Javadoc 참고).
 * Service 측은 이 호출 결과를 fail-open 정책으로 try/catch 한다 — 지갑 생성 호출이 실패해도
 * 인증 자체는 commit하고 WARN 로깅만 남긴다(API spec §10 사이드이펙트).
 */
public interface WalletClient {

    /**
     * 사용자당 1개의 전자지갑을 보장한다(멱등). 이미 있으면 wallet-service가 기존 지갑을 그대로 반환한다.
     *
     * @param userPublicId 회원 대외 식별자(UUID)
     */
    void createWalletFor(String userPublicId);
}
