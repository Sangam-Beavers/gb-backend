package com.gb.wallet.domain.transaction.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.transaction.service.TransferPinService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.config.PinVerifyRateLimitProperties;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.PinVerificationStore;
import com.gb.wallet.global.redis.RateLimitHelper;
import com.gb.wallet.global.redis.TransferPinAttemptStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferPinServiceImpl implements TransferPinService {

    /** PIN 검증 rate-limit 카운터 키 prefix(사용자 단위 — wallet-pin-redis-1). */
    private static final String PIN_VERIFY_RATE_LIMIT_KEY_PREFIX = "ratelimit:pin-verify:";

    private final WalletRepository walletRepository;
    private final TransferPinAttemptStore attemptStore;
    private final PinVerificationStore pinVerificationStore;
    private final RateLimitHelper rateLimitHelper;
    private final PinVerifyRateLimitProperties rateLimitProperties;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void setPin(String userPublicId, String pin) {
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // WTX-05 — 동결(SUSPENDED)/폐쇄(CLOSED) 지갑은 PIN 설정도 차단(ACTIVE만 허용). 송금 PIN은 송금 실행의
        // 전제이므로, 송금 자체가 막힌 비활성 지갑에 PIN을 새로 거는 것은 의미가 없고 상태 일관성도 깨진다.
        // 충전/송금과 동일한 WALLET4003(422) 가드.
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException(WalletErrorCode.WALLET_INACTIVE);
        }

        // 최초 설정만 허용. 이미 있으면 충돌(변경은 기존 PIN 확인이 필요한 후속 과제).
        if (wallet.hasTransferPin()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        // 평문 PIN은 저장하지 않고 BCrypt 해시만 저장. dirty checking으로 UPDATE.
        wallet.changeTransferPin(passwordEncoder.encode(pin));

        // 새 PIN은 직전 검증을 물려받지 않는다 — 잔존 마커를 무효화(방어적 불변식, TX-PIN). 현재는 최초
        // 설정만 허용해 마커가 있을 경로가 없지만, 향후 변경(change-PIN) 도입 대비.
        pinVerificationStore.clearVerified(userPublicId);
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyPin(String userPublicId, String pin) {
        // 0) 사용자 단위 고정 윈도 rate-limit — 무차별 대입 throttle bypass 차단(wallet-pin-redis-1).
        //    isLocked→BCrypt 대조→recordFailure는 비원자라, 동시 버스트가 모두 isLocked 게이트를 통과해
        //    잠금이 걸리기 전에 다수의 추측(BCrypt 대조)을 수행할 수 있다. 잠금 카운터와 별개로 *요청 빈도*를
        //    윈도당 limit으로 캡해 버스트를 막는다(transfer/account-holder와 동일 패턴). limit 기본값은 단기
        //    잠금 임계(5회)와 동일하게 캡해 버스트 추측 상한이 잠금 임계를 넘지 못한다(10D wallet-pin-redis-2).
        //    위조불가 userPublicId로 키잉. 초과 시 COMMON4291(429). Redis 장애 시 fail-open(통과). 잠금/마커보다
        //    먼저 — 차단된 요청은 BCrypt 대조도 markVerified도 하지 않는다.
        boolean allowed = rateLimitHelper.tryAcquire(
                PIN_VERIFY_RATE_LIMIT_KEY_PREFIX + userPublicId,
                rateLimitProperties.limit(),
                Duration.ofSeconds(rateLimitProperties.windowSeconds()));
        if (!allowed) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        // 1) 잠금 우선 확인 — 잠겨 있으면 대조 자체를 하지 않는다(무차별 대입 차단).
        if (attemptStore.isLocked(userPublicId)) {
            throw new BusinessException(TransferErrorCode.PIN_LOCKED);
        }

        // 2) 지갑/PIN 존재 확인.
        Wallet wallet = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        if (!wallet.hasTransferPin()) {
            throw new BusinessException(TransferErrorCode.PIN_NOT_SET);
        }

        // 3) 해시 대조. 불일치면 실패 기록 — 이번 실패로 잠기면 PIN_LOCKED, 아니면 PIN_MISMATCH.
        if (!passwordEncoder.matches(pin, wallet.getTransferPinHash())) {
            boolean nowLocked = attemptStore.recordFailure(userPublicId);
            throw new BusinessException(
                    nowLocked ? TransferErrorCode.PIN_LOCKED : TransferErrorCode.PIN_MISMATCH);
        }

        // 4) 성공 — 실패 카운트/잠금 초기화 + 단명 검증 마커 발급(TX-PIN). 마커는 송금/정기설정 게이트
        //    (TransferPinGate)가 원자 소비(GETDEL)해 1회 검증이 1회 인가만 되게 한다. 마커 저장 실패는
        //    fail-closed로 전파(검증 실패 처리 — PinVerificationStore.markVerified).
        attemptStore.reset(userPublicId);
        pinVerificationStore.markVerified(userPublicId);
    }
}
