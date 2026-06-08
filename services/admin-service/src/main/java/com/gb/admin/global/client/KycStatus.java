package com.gb.admin.global.client;

/**
 * 관리자 화면에서 보는 KYC 상태. member-service의 실제 enum과 동일 값을 둘 의도이지만
 * MSA 경계를 넘으므로 admin-service는 자기 사본을 둔다(SCREAMING_SNAKE_CASE).
 */
public enum KycStatus {

    PENDING,
    APPROVED,
    REJECTED,
    NEEDS_REVIEW,
    MATCHED
}
