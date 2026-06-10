package com.gb.member.domain.member.service.impl;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import com.gb.member.domain.member.service.TrustGradeService;
import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import com.gb.member.domain.milestone.repository.MemberMilestoneRepository;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신뢰등급 재계산 구현 (이슈 #193 / Phase 2 BE-5 — 인터페이스 javadoc 참고).
 *
 * <p>{@code @Transactional(REQUIRED)}로 호출 측 트랜잭션에 합류한다 — 인증 승인/취소·마일스톤 수신과
 * 등급 반영이 같은 tx에서 원자적으로 커밋된다. 저장은 영속 엔티티 dirty checking(별도 save 불필요).
 *
 * <p><b>Phase 2 산정 규칙(순차 체인 — 스파이크 "등급 재계산 규칙 확장"):</b>
 * <pre>
 * TRUSTED   ← VERIFIED && BANK_ACCOUNT_CONNECTED && FIRST_TRANSACTION_COMPLETED
 * CONNECTED ← VERIFIED && BANK_ACCOUNT_CONNECTED
 * VERIFIED  ← is_verified
 * NEWCOMER  ← 기본
 * </pre>
 * 인증 취소(is_verified=false)면 마일스톤 보유와 무관하게 NEWCOMER — 기록(member_milestones)은
 * 보존되므로 재인증 시 같은 호출 한 번으로 즉시 CONNECTED/TRUSTED 복귀된다.
 * 계좌 미연결 상태의 FIRST_TRANSACTION_COMPLETED 단독 보유는 VERIFIED에 머문다(체인 위배 — 정상적으론
 * 거래가 계좌 연결을 전제하므로 희박하지만, 이벤트 도착 순서 역전 시에도 규칙이 일관된다).
 */
@Service
@RequiredArgsConstructor
public class TrustGradeServiceImpl implements TrustGradeService {

    private final MemberMilestoneRepository memberMilestoneRepository;

    @Override
    @Transactional
    public TrustGrade recalculate(Member member) {
        TrustGrade grade = determine(member);
        member.applyTrustGrade(grade);
        return grade;
    }

    /**
     * 호출 시점의 현재 상태(is_verified + 달성 마일스톤)로 등급을 산정한다. 이력 누적이 아니라 매번
     * 전체 재산정이므로 역방향 전이(강등)도 같은 식 하나로 처리된다. Phase 3에서 GOLD(보너스 마일스톤)
     * 조건이 생기면 이 산정식에만 규칙을 더한다 — 호출부는 그대로 이 단일 진입점을 호출한다.
     */
    private TrustGrade determine(Member member) {
        // Lv2 관문: 신분증 인증. false면 마일스톤 보유와 무관하게 NEWCOMER(인증 취소 강등 — 기록은 보존).
        if (!member.isVerified()) {
            return TrustGrade.NEWCOMER;
        }

        // 달성 마일스톤 1회 조회(유형당 최대 1행 — UNIQUE). 미인증 회원은 위에서 끊겨 쿼리 비용 없음.
        Set<MilestoneType> achieved = memberMilestoneRepository
                .findAllByUserPublicId(member.getPublicId()).stream()
                .map(MemberMilestone::getMilestoneType)
                .collect(Collectors.toSet());

        if (!achieved.contains(MilestoneType.BANK_ACCOUNT_CONNECTED)) {
            return TrustGrade.VERIFIED;
        }
        if (!achieved.contains(MilestoneType.FIRST_TRANSACTION_COMPLETED)) {
            return TrustGrade.CONNECTED;
        }
        return TrustGrade.TRUSTED;
    }
}
