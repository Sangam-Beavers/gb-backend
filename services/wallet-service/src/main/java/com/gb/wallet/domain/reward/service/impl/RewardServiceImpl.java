package com.gb.wallet.domain.reward.service.impl;

import com.gb.wallet.domain.reward.dto.response.CouponListResponse;
import com.gb.wallet.domain.reward.dto.response.StampCardResponse;
import com.gb.wallet.domain.reward.entity.Coupon;
import com.gb.wallet.domain.reward.entity.CouponStatus;
import com.gb.wallet.domain.reward.entity.CouponType;
import com.gb.wallet.domain.reward.entity.RewardStamp;
import com.gb.wallet.domain.reward.repository.CouponRepository;
import com.gb.wallet.domain.reward.repository.RewardStampRepository;
import com.gb.wallet.domain.reward.service.RewardService;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.config.RewardProperties;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 송금 적립/쿠폰 도메인 서비스. 적립은 쓰기 트랜잭션, 조회는 readOnly.
 *
 * <p>적립({@link #accrueStamp})은 송금 커밋 후 {@code TransferStampEventListener}(AFTER_COMMIT)가 호출한다.
 * 멱등성은 ① 사전 존재 체크(빠른 경로) ② DB UNIQUE(최종 안전망) 2중으로 보장한다. 드문 동시성 race로
 * UNIQUE 위반이 나면 예외가 리스너로 전파돼 ERROR 로깅되며, 데이터는 이미 정합(스탬프 1·쿠폰 1) 상태다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewardServiceImpl implements RewardService {

    private final RewardStampRepository stampRepository;
    private final CouponRepository couponRepository;
    private final RewardProperties rewardProperties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accrueStamp(String userPublicId, String transactionPublicId, TransactionType transferType) {
        // 호출원이 AFTER_COMMIT 이벤트 리스너라 호출 시점엔 송금 트랜잭션이 이미 끝나(스레드에 매달린 채)
        // 있다. 기본 REQUIRED면 그 "이미 커밋된" 트랜잭션에 합류해 적립 INSERT가 커밋되지 못하고 사라진다
        // (예외도 없음). 그래서 REQUIRES_NEW로 독립 트랜잭션을 강제해 적립을 확실히 커밋한다
        // (코드베이스의 ChargeAttemptWriter/WalletBalanceWriter 등 REQUIRES_NEW writer 패턴과 동일).

        // (0) 적립/쿠폰 대상은 "수수료가 발생하는 타행 송금(REMITTANCE)"만이다. 앱 내 송금(INTERNAL_TRANSFER)은
        //     수수료가 없어 'TRANSFER_FEE_FREE(송금 수수료 무료)' 쿠폰 적립 대상이 아니므로 스탬프도 쌓지 않는다.
        //     그 외 유형도 방어적으로 제외(이벤트는 REMITTANCE/INTERNAL만 발행되지만 단일 진실 규칙으로 여기서 차단).
        if (transferType != TransactionType.REMITTANCE) {
            return;
        }

        // (1) 멱등 — 같은 송금 거래로 이미 적립됐으면 스킵(이벤트 중복/재시도 방어). UNIQUE가 최종 안전망.
        if (stampRepository.existsBySourceTransactionPublicId(transactionPublicId)) {
            return;
        }

        // (2) 스탬프 1건 적립(append-only).
        stampRepository.save(RewardStamp.builder()
                .userPublicId(userPublicId)
                .sourceTransactionPublicId(transactionPublicId)
                .transferType(transferType)
                .build());

        // (3) 쿠폰 발급 판정 — 방금 적립분을 포함한 누적 스탬프로 완성한 카드 수(=누적/목표)를 계산한다.
        //     %==0 대신 (누적/목표)로 "완성한 카드 수"를 직접 구해, 동시성으로 카운트를 살짝 다르게 읽어도
        //     완성 사이클을 빠뜨리지 않게 한다(self-heal). 사이클은 스탬프당 최대 1씩만 증가하므로 최상위
        //     사이클만 보장하면 충분하다.
        int target = rewardProperties.stampsPerCoupon();
        long totalStamps = stampRepository.countByUserPublicId(userPublicId);
        int completedCycles = (int) (totalStamps / target);
        if (completedCycles >= 1 && !couponRepository.existsByUserPublicIdAndCycleNo(userPublicId, completedCycles)) {
            issueCoupon(userPublicId, completedCycles);
        }
    }

    private void issueCoupon(String userPublicId, int cycleNo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        couponRepository.save(Coupon.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .type(CouponType.TRANSFER_FEE_FREE)
                .status(CouponStatus.ISSUED)
                .cycleNo(cycleNo)
                .issuedAt(now)
                .expiresAt(now.plusDays(rewardProperties.couponValidDays()))
                .build());
        log.info("송금 적립 쿠폰 발급 — user={}, cycle={}, type={}", userPublicId, cycleNo, CouponType.TRANSFER_FEE_FREE);
    }

    @Override
    public StampCardResponse getStampCard(String userPublicId) {
        long totalStamps = stampRepository.countByUserPublicId(userPublicId);
        long availableCoupons = couponRepository.countByUserPublicIdAndStatus(userPublicId, CouponStatus.ISSUED);
        return StampCardResponse.of(totalStamps, rewardProperties.stampsPerCoupon(), availableCoupons);
    }

    @Override
    public CouponListResponse getCoupons(String userPublicId) {
        return CouponListResponse.from(couponRepository.findByUserPublicIdOrderByIssuedAtDesc(userPublicId));
    }
}
