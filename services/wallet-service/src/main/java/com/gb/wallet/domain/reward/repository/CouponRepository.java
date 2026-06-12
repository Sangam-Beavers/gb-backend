package com.gb.wallet.domain.reward.repository;

import com.gb.wallet.domain.reward.entity.Coupon;
import com.gb.wallet.domain.reward.entity.CouponStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /** 해당 사이클로 이미 발급됐는지(멱등 사전 체크). (user, cycle_no) UNIQUE와 함께 이중 안전망. */
    boolean existsByUserPublicIdAndCycleNo(String userPublicId, int cycleNo);

    /** 보유 쿠폰 목록(발급 최신순). */
    List<Coupon> findByUserPublicIdOrderByIssuedAtDesc(String userPublicId);

    /** 특정 상태(예: 사용 가능 = ISSUED) 쿠폰 개수. */
    long countByUserPublicIdAndStatus(String userPublicId, CouponStatus status);
}
