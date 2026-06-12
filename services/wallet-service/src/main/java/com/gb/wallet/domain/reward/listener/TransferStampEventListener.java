package com.gb.wallet.domain.reward.listener;

import com.gb.wallet.domain.reward.service.RewardService;
import com.gb.wallet.global.event.TransferStampEarned;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 송금 적립 스탬프 이벤트({@link TransferStampEarned})를 <b>커밋 후</b>({@code AFTER_COMMIT}) 받아
 * 적립 + 쿠폰 발급을 수행한다(#215).
 *
 * <p><b>AFTER_COMMIT인 이유:</b> 송금 트랜잭션이 롤백되면 이 리스너는 호출되지 않는다 — 롤백된 송금에
 * 스탬프가 붙는 유령 적립을 구조적으로 차단한다({@code MilestoneEventPublisher}와 동일 사상). 리스너 시점엔
 * 원 트랜잭션이 끝나 있으므로, {@link RewardService#accrueStamp}의 {@code @Transactional}이 <b>새 트랜잭션</b>을
 * 열어 적립을 커밋한다(동기 실행이라 송금 API 응답 반환 전에 적립이 끝나 프론트가 곧장 stamp-card를 조회해도
 * 최신값을 본다).
 *
 * <p><b>적립 실패가 송금에 영향 없게:</b> 송금은 이미 커밋됐고 적립은 부가 보상이므로, 적립 중 예외(드문
 * 동시성 race의 UNIQUE 위반 등)는 여기서 흡수하고 ERROR 로깅만 한다 — AFTER_COMMIT 리스너의 예외는 호출
 * 스레드(API 응답 경로)로 전파되므로 반드시 삼킨다({@code MilestoneEventPublisher} 정책 답습).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferStampEventListener {

    private final RewardService rewardService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferStampEarned(TransferStampEarned event) {
        try {
            rewardService.accrueStamp(
                    event.userPublicId(), event.transactionPublicId(), event.transferType());
        } catch (RuntimeException e) {
            log.error("송금 적립 처리 실패 — 보정 대상. user_public_id={}, transaction_public_id={}, transfer_type={}",
                    event.userPublicId(), event.transactionPublicId(), event.transferType(), e);
        }
    }
}
