package com.gb.wallet.global.client;

import java.math.BigDecimal;

/**
 * app-admin-service의 수수료 정책을 조회하는 클라이언트 계약.
 *
 * <p>수수료 정책은 관리자가 언제든 변경할 수 있으므로, 코드 상수 대신 이 클라이언트를 통해
 * 런타임에 조회한다. 결과는 Redis에 5분 TTL로 캐시된다(AppAdminClientCacheWrapper).
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link MockAppAdminClient} — {@code @Profile("dev | test")}: 고정 값 반환 (연동 없음).</li>
 *   <li>{@link RealAppAdminClient} — {@code @Profile("!dev & !test")}: {@code GET /api/v1/app-admin/app/fee-policies} 호출.</li>
 * </ul>
 */
public interface AppAdminClient {

    /**
     * EXCHANGE(환전) 수수료율을 조회한다.
     * feeType=PERCENT이면 비율(예: 0.015 = 1.5%), FIXED이면 고정 금액(KRW).
     *
     * @return 수수료 정책 정보
     */
    FeePolicy getExchangeFeePolicy();

    /**
     * CASHOUT(외부 은행 출금) 수수료 정책을 조회한다.
     *
     * @return 수수료 정책 정보
     */
    FeePolicy getCashoutFeePolicy();

    record FeePolicy(String feeType, BigDecimal feeValue, BigDecimal minFee, BigDecimal maxFee) {

        /**
         * feeType이 PERCENT일 때 소수 비율(0~1)로 변환한다.
         * DB에는 퍼센트 값(예: 1.5 = 1.5%)으로 저장되므로 /100 처리.
         */
        public BigDecimal rateAsDecimal() {
            return feeValue.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP);
        }

        public boolean isPercent() {
            return "PERCENT".equalsIgnoreCase(feeType);
        }
    }
}
