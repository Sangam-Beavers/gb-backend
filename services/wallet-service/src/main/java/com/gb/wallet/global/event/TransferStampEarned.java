package com.gb.wallet.global.event;

import com.gb.wallet.global.common.enums.TransactionType;

/**
 * 송금 적립 스탬프 획득 <b>내부</b> Spring ApplicationEvent (#215 — 송금 적립/쿠폰).
 *
 * <p>송금 서비스({@code TransferServiceImpl})가 송금 트랜잭션 안에서 이 가벼운 이벤트를 발행하면,
 * 커밋 후({@code AFTER_COMMIT}) {@code TransferStampEventListener}가 받아 스탬프 적립 + 쿠폰 발급을
 * 수행한다. 커밋 전 발행(롤백됐는데 적립만 되는 유령 적립)을 구조적으로 차단한다({@link MilestoneAchieved}와
 * 동일 사상). 트랜잭션이 롤백되면 리스너는 호출되지 않는다.
 *
 * <p><b>{@link MilestoneAchieved}와 별개 이벤트인 이유:</b> 마일스톤(신뢰등급)은 충전도 포함한 전체 거래에서
 * 발행되지만, 적립 스탬프는 <b>송금(INTERNAL_TRANSFER / REMITTANCE)만</b> 대상이다. 같은 이벤트를 재사용하면
 * 충전까지 적립되므로 송금 경로 전용 이벤트를 따로 둔다. 소비자가 같은 wallet-service라 Kafka가 아닌
 * in-process 이벤트로 처리한다(MSA 경계 없음).
 *
 * @param userPublicId           적립 대상 회원의 public_id(UUID — MSA 경계 참조, CLAUDE §7)
 * @param transactionPublicId    적립을 만든 송금 거래의 public_id(멱등 키)
 * @param transferType           송금 유형(감사용 — INTERNAL_TRANSFER / REMITTANCE)
 */
public record TransferStampEarned(String userPublicId, String transactionPublicId,
                                  TransactionType transferType) {
}
