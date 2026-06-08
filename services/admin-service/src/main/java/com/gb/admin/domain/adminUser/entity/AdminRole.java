package com.gb.admin.domain.adminUser.entity;

/**
 * 관리자 역할(role). SCREAMING_SNAKE_CASE.
 *
 * <p>Phase 1은 group claim 기반 RBAC를 적용하지 않고 {@code authenticated()}로만 보호한다.
 * 본 enum은 {@link AdminUserRole}을 통해 DB 저장만 한다(추후 스프린트에서 권한 검사에 사용).
 */
public enum AdminRole {

    /** 슈퍼 관리자 — 전체 권한. */
    SUPER,
    /** 고객 지원(Customer Support) — KYC 승인/거절, 거래 조회. */
    CS,
    /** 컴플라이언스 — 이상거래 검토. */
    COMPLIANCE,
    /** 재무(Finance) — 정산/한도 정책. */
    FINANCE
}
