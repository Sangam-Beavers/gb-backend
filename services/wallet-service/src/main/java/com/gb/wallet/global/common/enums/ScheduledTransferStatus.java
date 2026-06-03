package com.gb.wallet.global.common.enums;

/**
 * 정기 송금 상태.
 *
 * <ul>
 *   <li>{@code ACTIVE} — 다음 실행 예정일에 자동 실행 대상. 설정 직후 기본값.</li>
 *   <li>{@code PAUSED} — 사용자 또는 시스템이 일시정지. 자동 실행 제외. 재개 API로 다시 ACTIVE로 전환(추후).</li>
 *   <li>{@code CANCELLED} — 사용자가 취소. 영구 중단. 재개 불가(소프트 삭제 의미).</li>
 * </ul>
 *
 * <p>스케줄러는 ACTIVE만 조회·실행 대상으로 본다. PAUSED/CANCELLED는 자동 실행에서 제외.
 */
public enum ScheduledTransferStatus {
    ACTIVE,
    PAUSED,
    CANCELLED
}
