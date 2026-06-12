package com.gb.wallet.domain.reward.service;

import com.gb.wallet.domain.reward.dto.response.CouponListResponse;
import com.gb.wallet.domain.reward.dto.response.StampCardResponse;
import com.gb.wallet.global.common.enums.TransactionType;

public interface RewardService {

    /**
     * 송금 1건 완료에 대한 스탬프 적립 + 쿠폰 발급(목표 충족 시).
     *
     * <p><b>멱등:</b> {@code transactionPublicId}로 이미 적립된 거래면 아무것도 하지 않는다(스탬프
     * {@code source_transaction_public_id} UNIQUE, 쿠폰 {@code (user, cycle_no)} UNIQUE가 DB 레벨 최종 안전망).
     * 송금 커밋 후 이벤트 리스너가 호출하며, 이벤트 중복/재시도에도 중복 적립·중복 발급이 없다.
     *
     * @param userPublicId        적립 대상 회원 public_id
     * @param transactionPublicId 적립을 만든 송금 거래 public_id(멱등 키)
     * @param transferType        송금 유형(감사용 — INTERNAL_TRANSFER / REMITTANCE)
     */
    void accrueStamp(String userPublicId, String transactionPublicId, TransactionType transferType);

    /** 현재 스탬프 카드 진행도 조회(채워진 칸/목표/누적/사용 가능 쿠폰 수). */
    StampCardResponse getStampCard(String userPublicId);

    /** 보유 쿠폰 목록 조회(발급 최신순). */
    CouponListResponse getCoupons(String userPublicId);
}
