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
 * <p>Phase 3(GOLD 보너스 마일스톤 4종) — 각 발행 서비스·토픽에서 이벤트를 수신해 기록한다.
 * ACCOUNT_NINETY_DAYS는 이벤트가 아니라 가입일(createdAt) 기반 합성값이라 DB에 저장하지 않고
 * 현황 API(BE-6) 조회 시 실시간 계산한다. 미지의 milestone_type 수신은 WARN 스킵(전방 호환).
 */
public enum MilestoneType {

    /** 계좌 인증·연결 완료 (Lv3 CONNECTED 조건). 발행: wallet-service. */
    BANK_ACCOUNT_CONNECTED,

    /** 첫 금융 거래(충전/송금/현금화) COMPLETED (Lv4 TRUSTED 조건). 발행: wallet-service. */
    FIRST_TRANSACTION_COMPLETED,

    // ── Phase 3 GOLD 보너스 마일스톤 (4종 중 2개 이상 달성 시 Lv5 GOLD) ──────────────

    /** AI 서류 분석 1건 이상 완료. 발행: document-service. */
    DOCUMENT_ANALYZED,

    /** 커뮤니티 게시글 또는 댓글 1건 이상 작성. 발행: community-service. */
    COMMUNITY_ACTIVE,

    /**
     * 누적 금융 거래(충전·송금·현금화) 5건 이상 완료. 발행: wallet-service.
     * FIRST_TRANSACTION_COMPLETED(1건)와 별개 — 5건 달성 시 추가로 기록된다.
     */
    TRANSACTION_FIVE_COMPLETED
    // ACCOUNT_NINETY_DAYS는 createdAt 기반 합성값 — DB 저장 없음. TrustGradeServiceImpl/MilestoneServiceImpl 참고.
}
