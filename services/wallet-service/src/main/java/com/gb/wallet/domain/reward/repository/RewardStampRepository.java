package com.gb.wallet.domain.reward.repository;

import com.gb.wallet.domain.reward.entity.RewardStamp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardStampRepository extends JpaRepository<RewardStamp, Long> {

    /** 같은 송금 거래로 이미 적립됐는지(멱등 사전 체크). UNIQUE 제약과 함께 이중 안전망. */
    boolean existsBySourceTransactionPublicId(String sourceTransactionPublicId);

    /** 사용자 누적 스탬프 개수(전체). 쿠폰 발급 사이클 판정·현재 카드 진행도 계산에 사용. */
    long countByUserPublicId(String userPublicId);
}
