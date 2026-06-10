package com.gb.member.domain.milestone.repository;

import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberMilestoneRepository extends JpaRepository<MemberMilestone, Long> {

    /** 중복 수신 선검사(멱등 스킵)용 — (user_public_id, milestone_type)는 UNIQUE라 0/1건. */
    boolean existsByUserPublicIdAndMilestoneType(String userPublicId, MilestoneType milestoneType);

    /**
     * 회원의 달성 마일스톤 전체 — 등급 재계산(BE-5)과 현황 API(BE-6)가 쓴다.
     * 유형당 1행(UNIQUE)이고 Phase 2 카탈로그가 2종이라 결과는 최대 2건.
     */
    List<MemberMilestone> findAllByUserPublicId(String userPublicId);
}
