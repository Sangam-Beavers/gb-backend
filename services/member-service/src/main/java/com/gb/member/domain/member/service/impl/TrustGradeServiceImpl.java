package com.gb.member.domain.member.service.impl;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import com.gb.member.domain.member.service.TrustGradeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신뢰등급 재계산 구현 (이슈 #193 — 인터페이스 javadoc 참고).
 *
 * <p>{@code @Transactional(REQUIRED)}로 호출 측 트랜잭션에 합류한다 — 인증 승인/취소와 등급 반영이
 * 같은 tx에서 원자적으로 커밋된다. 저장은 영속 엔티티 dirty checking(별도 save 불필요).
 */
@Service
public class TrustGradeServiceImpl implements TrustGradeService {

    @Override
    @Transactional
    public TrustGrade recalculate(Member member) {
        // Phase 1(Lv1~2): 신분증 인증 승인 여부만 본다. Phase 2에서 상위 마일스톤(커뮤니티 활동·송금 실적 등)
        // 조건이 추가되면 이 산정식에만 규칙을 더한다 — 호출부(인증 서비스·관리자 KYC·Phase 2 Kafka Consumer)는
        // 그대로 이 단일 진입점을 호출한다.
        TrustGrade grade = member.isVerified() ? TrustGrade.VERIFIED : TrustGrade.NEWCOMER;
        member.applyTrustGrade(grade);
        return grade;
    }
}
