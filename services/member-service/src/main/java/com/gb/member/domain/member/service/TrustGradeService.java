package com.gb.member.domain.member.service;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;

/**
 * 신뢰등급(trust_grade) 재계산 <b>단일 진입점</b> (이슈 #193 / Phase 2 BE-5).
 *
 * <p>등급에 영향을 주는 마일스톤 이벤트(신분증 인증 승인/취소, wallet 마일스톤 수신)가 발생한 지점에서
 * <b>같은 트랜잭션 안에서</b> 호출한다. 이력 누적 방식이 아니라 호출 시점의 회원 상태로
 * 등급을 다시 산정해 반영하므로, 승인 취소 등 역방향 전이(강등)도 같은 메서드 하나로 처리된다.
 *
 * <p>호출부(Phase 2 기준 3곳): 인증 서비스(VerificationServiceImpl) · 관리자 KYC(MemberAdminInternalServiceImpl)
 * · Kafka Consumer(MilestoneServiceImpl — wallet.milestone-achieved.v1 수신). 산정 규칙은 이 진입점
 * 한 곳에만 존재하도록 유지할 것(호출부에 규칙 복제 금지).
 */
public interface TrustGradeService {

    /**
     * 회원의 신뢰등급을 현재 마일스톤 충족 상태 기준으로 재계산해 반영한다.
     *
     * <p>Phase 2 규칙(순차 체인): {@code is_verified=false} → {@link TrustGrade#NEWCOMER}.
     * true면 member_milestones를 보아 BANK_ACCOUNT_CONNECTED 보유 시 {@link TrustGrade#CONNECTED},
     * + FIRST_TRANSACTION_COMPLETED 보유 시 {@link TrustGrade#TRUSTED}, 둘 다 없으면
     * {@link TrustGrade#VERIFIED}. (GOLD는 Phase 3)
     *
     * @param member 영속 상태의 회원 엔티티(호출 측 트랜잭션 안). 변경은 dirty checking으로 저장된다.
     * @return 산정된 등급
     */
    TrustGrade recalculate(Member member);
}
