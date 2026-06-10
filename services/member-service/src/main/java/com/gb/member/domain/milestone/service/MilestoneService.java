package com.gb.member.domain.milestone.service;

import com.gb.member.domain.milestone.dto.event.MilestoneAchievedEvent;
import com.gb.member.domain.milestone.dto.response.TrustMilestonesResponse;

/**
 * 신뢰등급 마일스톤 도메인 서비스 (Phase 2 — BE-4/BE-6).
 *
 * <p>쓰기(이벤트 수신 기록)와 읽기(현황 API)를 한 도메인 서비스로 묶는다(기능을 과하게 쪼개지 않는다 —
 * CLAUDE §1). 등급 산정 규칙은 여기 두지 않는다 — {@code TrustGradeService.recalculate} 단일 진입점만 호출.
 */
public interface MilestoneService {

    /**
     * 마일스톤 달성 이벤트를 기록하고 신뢰등급을 재계산한다(한 트랜잭션 — 스파이크 결정 3).
     *
     * <p>멱등: 이미 기록된 (user_public_id, milestone_type)이면 조용히 스킵한다(자연 멱등 —
     * 마일스톤은 영구 달성이라 중복 적용해도 결과 동일). 동시 중복 수신이 선검사를 함께 통과한
     * race는 UNIQUE 충돌 → 컨테이너 재시도에서 선검사에 걸려 스킵으로 수렴한다.
     *
     * @param event Kafka로 수신한 발행측 이벤트(컨슈머측 미러 DTO)
     */
    void recordMilestone(MilestoneAchievedEvent event);

    /**
     * 본인 마일스톤 현황 조회(BE-6) — 현재 등급 + 카탈로그 전체(미달성 포함).
     *
     * @param userPublicId 토큰에서 추출한 본인 public_id
     * @throws com.gb.common.exception.BusinessException MEMBER4001 — 회원 없음(탈퇴 포함)
     */
    TrustMilestonesResponse getMyTrustMilestones(String userPublicId);
}
