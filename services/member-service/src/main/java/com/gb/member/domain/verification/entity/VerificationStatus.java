package com.gb.member.domain.verification.entity;

/**
 * 신분증 인증 상태. database.md §user_verifications — PENDING/APPROVED/REJECTED.
 *
 * <p>현재 데모 구현은 형식 검증 통과 시 즉시 {@link #APPROVED}로 처리한다(관리자 검토 단계 생략).
 * 실 운영에서 관리자 검토 흐름을 붙이면 {@link #PENDING} 접수 → 검토 후 APPROVED/REJECTED로 전이한다.
 */
public enum VerificationStatus {
    PENDING,
    APPROVED,
    REJECTED
}
