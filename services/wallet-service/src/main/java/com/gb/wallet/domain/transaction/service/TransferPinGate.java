package com.gb.wallet.domain.transaction.service;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.PinVerificationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 송금 PIN 서버측 인가 게이트(TX-PIN). {@code /pin-verify} 성공 증표(단명 마커)를 원자 소비해, 마커가 있을
 * 때만 자금 이동을 인가한다. 명세 §5/§6: PIN 검증 성공 후에만 송금 실행 가능.
 *
 * <p><b>공용 게이트:</b> 수동 송금({@code TransferServiceImpl.execute})과 정기송금 설정
 * ({@code ScheduledTransferServiceImpl.create})이 같은 인가 규칙을 쓰도록 한 곳에 모은다 — 정기송금은
 * "설정 시 1회" 이 게이트로 인가(standing order)하고 회차 실행은 면제한다.
 *
 * <p><b>consume-first:</b> 정상 경로(마커 존재)는 마커만 원자 소비하고 끝난다(추가 DB 조회 없음). 마커가
 * 없을 때만 지갑을 조회해 두 거부 사유를 구분한다:
 * <ul>
 *   <li>PIN 미설정({@code !hasTransferPin}) → {@link TransferErrorCode#PIN_NOT_SET}(TRANSFER4009, 400) —
 *       "먼저 PIN을 설정하라". (PIN을 설정한 적 없으면 마커를 만들 수 없으므로 항상 이 분기로 들어온다.)</li>
 *   <li>PIN은 있으나 미검증/만료 → {@link TransferErrorCode#PIN_VERIFICATION_REQUIRED}(TRANSFER4010, 428) —
 *       "먼저 PIN을 검증하라".</li>
 * </ul>
 * 마커가 있으면 PIN은 반드시 설정돼 있으므로(검증 없이는 마커 발급 불가), consume-first가 마커를 잘못
 * 태우는 일은 없다.
 *
 * <p><b>fail-closed:</b> Redis 장애 시 {@link PinVerificationStore#consumeVerified}가 COMMON5000을 전파해
 * 송금을 차단한다 — rate-limit의 fail-open과 의도적으로 다르다(금융 2차인증).
 */
@Component
@RequiredArgsConstructor
public class TransferPinGate {

    private final PinVerificationStore pinVerificationStore;
    private final WalletRepository walletRepository;

    /**
     * 직전 PIN 검증 마커를 원자 소비해 송금/정기설정을 인가한다. 마커가 없으면 사유에 따라 TRANSFER4009(미설정)
     * 또는 TRANSFER4010(미검증)을 던진다. 성공 시 마커는 소비돼(단일사용) 다음 송금은 재검증을 요구한다.
     *
     * @param userPublicId 송신자(요청자)
     * @throws BusinessException TRANSFER4009/TRANSFER4010, 또는 마커 부재 + 지갑 부재 시 WALLET4001, Redis 장애 시 COMMON5000
     */
    public void requireVerified(String userPublicId) {
        if (pinVerificationStore.consumeVerified(userPublicId)) {
            return; // 정상 — 마커 1회 소비. 추가 DB 조회 없이 통과.
        }
        // 마커 없음 — PIN 미설정(4009)인지 미검증(4010)인지 구분해 명확한 사유로 거부한다.
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        throw new BusinessException(wallet.hasTransferPin()
                ? TransferErrorCode.PIN_VERIFICATION_REQUIRED
                : TransferErrorCode.PIN_NOT_SET);
    }
}
