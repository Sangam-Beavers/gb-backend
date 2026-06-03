package com.gb.wallet.domain.transaction.service;

/**
 * 송금 PIN(별도 PIN) 설정/검증.
 *
 * <p>방식 B라 계정 비밀번호는 우리 DB에 없으므로(IdP 보유), 송금 전 본인확인은 별도의 송금 PIN으로 한다.
 * PIN 해시는 wallets에 저장하고, 연속 실패/잠금은 Redis(TTL)로 관리한다.
 */
public interface TransferPinService {

    /** 송금 PIN을 최초 설정한다(6자리). 이미 설정돼 있으면 COMMON4091. 지갑 없으면 WALLET4001. */
    void setPin(String userPublicId, String pin);

    /**
     * 송금 PIN을 검증한다. 성공 시 실패 카운트를 리셋한다.
     * 잠금 중이면 TRANSFER4008, 미설정이면 TRANSFER4009, 불일치면 TRANSFER4007(누적 초과 시 잠금→4008).
     */
    void verifyPin(String userPublicId, String pin);
}
