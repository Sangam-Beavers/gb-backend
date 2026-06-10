package com.gb.member.domain.milestone.entity;

import com.gb.member.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 마일스톤 달성 기록 (Phase 2 — BE-4, 스파이크 결정 3).
 *
 * <p><b>자연 멱등의 근거:</b> {@code (user_public_id, milestone_type)} UNIQUE. 마일스톤은 "영구 달성"
 * 사실 기록이라 wallet이 같은 이벤트를 중복 발행(at-least-once + 매번 발행 전략)해도 INSERT가
 * 유니크 충돌로 막혀 결과가 동일하다 — event_id 별도 dedup 테이블 불필요. 인증 취소로 등급이
 * 강등돼도 이 기록은 보존된다(재인증 시 즉시 CONNECTED+ 복귀 — BE-5 순차 체인).
 *
 * <p>{@code user_public_id}는 MSA 경계를 넘는 회원 참조(CLAUDE §7) — wallet 이벤트가 주는 값
 * 그대로 보관하고 members와 물리 FK를 걸지 않는다(같은 스키마지만 이벤트 출처가 외부 서비스라
 * 도착 순서·회원 탈퇴와 무관하게 기록 자체는 남긴다). {@code event_id}는 추적/로깅용 메타.
 *
 * <p>{@code achieved_at}은 발행측 occurred_at(UTC) 스냅샷 — 수신(컨슈머 처리) 시각이 아니라
 * "달성 시각"을 보존한다. createdAt(수신 시각)은 BaseEntity Auditing이 채운다.
 */
@Entity
@Table(name = "member_milestones",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_milestones_user_type",
                columnNames = {"user_public_id", "milestone_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberMilestone extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 달성 회원 public_id(UUID). 경계 넘는 참조라 BIGINT FK를 걸지 않는다(CLAUDE §7). */
    @Column(name = "user_public_id", nullable = false, length = 36)
    private String userPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "milestone_type", nullable = false, length = 40)
    private MilestoneType milestoneType;

    /** 달성 시각(UTC) — 발행 이벤트의 occurred_at 스냅샷. */
    @Column(name = "achieved_at", nullable = false)
    private LocalDateTime achievedAt;

    /** 달성을 기록한 이벤트의 event_id(UUID) — 추적/로깅용 메타(멱등 키 아님 — 멱등은 UNIQUE 제약). */
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Builder
    private MemberMilestone(String userPublicId, MilestoneType milestoneType,
                            LocalDateTime achievedAt, String eventId) {
        this.userPublicId = userPublicId;
        this.milestoneType = milestoneType;
        this.achievedAt = achievedAt;
        this.eventId = eventId;
    }
}
