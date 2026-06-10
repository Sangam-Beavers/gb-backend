package com.gb.member.domain.member.entity;

/**
 * 회원 계정 상태.
 * ACTIVE : 정상(기본값)
 * SUSPENDED : 관리자가 정지한 계정 — 로그인은 가능하나 송금 등 금융 기능이 차단된다(미래 연동).
 */
public enum MemberStatus {
    ACTIVE,
    SUSPENDED
}
