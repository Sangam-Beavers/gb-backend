package com.gb.member.domain.member.service;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;

/**
 * 신뢰등급(trust_grade) 재계산 <b>단일 진입점</b> (이슈 #193).
 *
 * <p>등급에 영향을 주는 마일스톤 이벤트(현재: 신분증 인증 승인/취소)가 발생한 지점에서
 * <b>같은 트랜잭션 안에서</b> 호출한다. 이력 누적 방식이 아니라 호출 시점의 회원 상태로
 * 등급을 다시 산정해 반영하므로, 승인 취소 등 역방향 전이도 같은 메서드 하나로 처리된다.
 *
 * <p><b>Phase 2 전제:</b> Kafka Consumer(커뮤니티 활동·송금 실적 등 마일스톤 이벤트 수신)도
 * 회원을 조회한 뒤 동일하게 이 {@link #recalculate}를 호출한다 — 산정 규칙이 이 진입점
 * 한 곳에만 존재하도록 유지할 것(호출부에 규칙 복제 금지).
 */
public interface TrustGradeService {

    /**
     * 회원의 신뢰등급을 현재 마일스톤 충족 상태 기준으로 재계산해 반영한다.
     *
     * <p>Phase 1 규칙: {@code is_verified=true} → {@link TrustGrade#VERIFIED}, 아니면 {@link TrustGrade#NEWCOMER}.
     *
     * @param member 영속 상태의 회원 엔티티(호출 측 트랜잭션 안). 변경은 dirty checking으로 저장된다.
     * @return 산정된 등급
     */
    TrustGrade recalculate(Member member);
}
