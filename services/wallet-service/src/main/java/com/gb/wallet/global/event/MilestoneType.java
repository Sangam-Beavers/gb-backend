package com.gb.wallet.global.event;

/**
 * wallet-service가 발행하는 신뢰등급 마일스톤 종류 (Phase 2 — 스파이크 결정 2).
 *
 * <p>JSON 와이어 값은 enum 이름 그대로 SCREAMING_SNAKE_CASE(컨벤션 §5). member-service의
 * Consumer 쪽 enum과 이름이 1:1로 일치해야 한다 — MSA 경계라 클래스는 공유하지 않고
 * <b>이름(문자열) 계약</b>만 공유한다(스키마 SSOT = Phase2 이벤트 컨벤션 스파이크 문서).
 */
public enum MilestoneType {

    /** 계좌 인증·연결 완료 (Lv3 마일스톤). */
    BANK_ACCOUNT_CONNECTED,

    /** 첫 금융 거래(충전/송금/현금화) COMPLETED (Lv4 마일스톤). "첫 건" 판단 없이 매번 발행 — 수신측 자연 멱등. */
    FIRST_TRANSACTION_COMPLETED
}
