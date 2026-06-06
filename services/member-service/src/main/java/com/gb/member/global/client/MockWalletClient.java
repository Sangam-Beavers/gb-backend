package com.gb.member.global.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 Mock {@link WalletClient}. wallet-service 실제 호출 없이 INFO 로깅만 한다.
 *
 * <p>활성 프로파일이 {@code test}일 때만 빈으로 등록된다 — H2/컨텍스트 로딩 테스트가 외부 HTTP 없이
 * 돌도록. <b>dev를 포함한 나머지 전 환경은 {@link RealWalletClient}</b>가 실제 wallet-service의
 * {@code POST /api/v1/wallets}를 호출한다(#155 Mock→Real 정렬 — dev에서도 인증 APPROVED 시 지갑
 * 자동개설(#152)이 실동작해 prod·stage와 행위가 같아진다. MemberClient 정렬과 동일 사상).
 */
@Slf4j
@Component
@Profile("test")
public class MockWalletClient implements WalletClient {

    @Override
    public void createWalletFor(String userPublicId) {
        log.info("[MockWalletClient] 지갑 생성 호출 skip (test). user_public_id={}", userPublicId);
    }
}
