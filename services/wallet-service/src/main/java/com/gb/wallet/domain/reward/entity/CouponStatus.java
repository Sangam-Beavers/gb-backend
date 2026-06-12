package com.gb.wallet.domain.reward.entity;

/**
 * 쿠폰 상태. 와이어 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(컨벤션 §5).
 *
 * <p>본 이슈 범위는 발급({@link #ISSUED})까지다. 사용({@link #USED})·만료({@link #EXPIRED}) 전이는
 * 후속 이슈(쿠폰 사용·만료 배치)에서 채운다 — 상태 값은 미리 정의해 스키마가 흔들리지 않게 한다.
 */
public enum CouponStatus {

    /** 발급됨(미사용). */
    ISSUED,

    /** 사용 완료. (후속 이슈 — 쿠폰 사용) */
    USED,

    /** 만료됨. (후속 이슈 — 만료 배치) */
    EXPIRED
}
