package com.gb.wallet.domain.reward.entity;

/**
 * 발급 쿠폰의 종류. 와이어 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(컨벤션 §5).
 *
 * <p>현재는 "송금 수수료 무료 1회"({@link #TRANSFER_FEE_FREE}) 한 종류만 발급한다. 쿠폰 <b>사용</b>
 * (수수료 무료 적용)은 본 이슈 범위 밖이며, 발급·조회까지만 다룬다. 보상 종류가 늘면(정률/정액 할인 등)
 * 여기에 값을 추가한다.
 */
public enum CouponType {

    /** 송금 1회 수수료 무료. */
    TRANSFER_FEE_FREE
}
