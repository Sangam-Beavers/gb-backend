package com.gb.member.global.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 Mock {@link WalletClient}. wallet-service 실제 호출 없이 INFO 로깅만 한다.
 *
 * <p>활성 프로파일이 {@code dev} 또는 {@code test}일 때만 빈으로 등록된다. 운영/스테이징에서는
 * {@link RealWalletClient}가 활성화되어 실제 wallet-service의 {@code POST /api/v1/wallets}를 호출한다.
 *
 * <p>개발기에서도 진짜 지갑 생성을 시험하고 싶으면 본 빈을 임시로 비활성화하거나(@Primary
 * RealWalletClient 빈 추가) 프로파일을 분리해 사용한다 — 본 이슈(#152) 범위 밖.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class MockWalletClient implements WalletClient {

    @Override
    public void createWalletFor(String userPublicId) {
        log.info("[MockWalletClient] 지갑 생성 호출 skip (dev/test). user_public_id={}", userPublicId);
    }
}
