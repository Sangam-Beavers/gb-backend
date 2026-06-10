package com.gb.member.domain.milestone.entity;

/**
 * member_milestones에 기록되는 신뢰등급 마일스톤 종류 (Phase 2 — BE-4).
 *
 * <p>발행자(wallet-service)의 enum과 클래스를 공유하지 않고 <b>이름(문자열) 계약</b>만 공유한다
 * (MSA 경계 — 스키마 SSOT는 Phase2 이벤트 컨벤션 스파이크 문서). 와이어 값은 SCREAMING_SNAKE_CASE.
 *
 * <p>신분증 인증(ID_VERIFIED)은 여기 넣지 않는다 — 그 사실의 SSOT는 {@code members.is_verified}
 * (+ user_verifications)이고, member_milestones는 <b>이벤트로 수신한 외부 서비스 마일스톤</b>만 기록한다.
 * 마일스톤 현황 API(BE-6)가 ID_VERIFIED를 is_verified로 합성해 카탈로그에 포함시킨다.
 *
 * <p>Phase 3에서 document/community 마일스톤(서류 분석·커뮤니티 활동)이 추가될 예정 — 각 토픽
 * ({@code document.milestone-achieved.v1} 등) 구독 시 이 enum에 값만 더한다. 미지의 milestone_type
 * 수신은 Consumer가 WARN 스킵한다(전방 호환).
 */
public enum MilestoneType {

    /** 계좌 인증·연결 완료 (Lv3 CONNECTED 조건). 발행: wallet-service. */
    BANK_ACCOUNT_CONNECTED,

    /** 첫 금융 거래(충전/송금/현금화) COMPLETED (Lv4 TRUSTED 조건). 발행: wallet-service. */
    FIRST_TRANSACTION_COMPLETED
}
