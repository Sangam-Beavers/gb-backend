package com.gb.member.domain.milestone.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.TrustGradeService;
import com.gb.member.domain.milestone.dto.event.MilestoneAchievedEvent;
import com.gb.member.domain.milestone.dto.response.TrustMilestonesResponse;
import com.gb.member.domain.milestone.dto.response.TrustMilestonesResponse.MilestoneItem;
import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import com.gb.member.domain.milestone.repository.MemberMilestoneRepository;
import com.gb.member.domain.milestone.service.MilestoneService;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import com.gb.member.domain.verification.repository.UserVerificationRepository;
import com.gb.member.global.exception.code.MemberErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마일스톤 기록(Kafka 수신) + 현황 조회(BE-6) 구현. 인터페이스 javadoc 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneServiceImpl implements MilestoneService {

    /** 현황 API 카탈로그의 합성 항목 — 신분증 인증(SSOT = members.is_verified, member_milestones에 저장 안 함). */
    private static final String ID_VERIFIED = "ID_VERIFIED";

    private final MemberMilestoneRepository memberMilestoneRepository;
    private final MemberRepository memberRepository;
    private final UserVerificationRepository userVerificationRepository;
    private final TrustGradeService trustGradeService;

    // ── 이벤트 수신 기록 (BE-4) ─────────────────────────────────────────────────

    @Override
    @Transactional
    public void recordMilestone(MilestoneAchievedEvent event) {
        // (1) milestone_type 변환 — 미지 값(추후 Phase 확장·발행측 선배포)은 WARN 스킵. 재시도해도
        //     결과가 같으니 예외로 DLT까지 보내지 않는다(전방 호환 — 컨슈머는 미지 값 무시).
        MilestoneType type;
        try {
            type = MilestoneType.valueOf(event.milestoneType());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            log.warn("미지의 milestone_type 수신 — 스킵(전방 호환). milestone_type={}, event_id={}",
                    event.milestoneType(), event.eventId());
            return;
        }

        // (2) 멱등 선검사 — 이미 달성 기록이 있으면 조용히 스킵(스파이크 결정 3: 마일스톤은 영구 달성).
        //     선검사를 동시에 통과한 중복 race는 (3) INSERT의 UNIQUE 충돌로 tx가 롤백되고, 컨테이너
        //     재시도(DefaultErrorHandler)가 다시 들어오면 이 선검사에 걸려 스킵으로 수렴한다 — DLT까지 안 감.
        if (memberMilestoneRepository.existsByUserPublicIdAndMilestoneType(event.userPublicId(), type)) {
            log.debug("이미 달성한 마일스톤 — 멱등 스킵. user_public_id={}, milestone_type={}, event_id={}",
                    event.userPublicId(), type, event.eventId());
            return;
        }

        // (3) 달성 기록 INSERT. achieved_at = 발행측 occurred_at(달성 시각 보존) — 누락 시 수신 시각 폴백.
        LocalDateTime achievedAt = event.occurredAt() != null
                ? LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC);
        memberMilestoneRepository.save(MemberMilestone.builder()
                .userPublicId(event.userPublicId())
                .milestoneType(type)
                .achievedAt(achievedAt)
                .eventId(event.eventId())
                .build());

        // (4) 같은 tx에서 등급 재계산 — 산정 규칙은 Phase 1 단일 진입점(TrustGradeService) 재사용.
        //     회원이 없으면(탈퇴 등) 기록은 남기되 재계산만 스킵 — 등급은 회원 행에 붙는 파생 데이터라
        //     대상이 없으면 할 일이 없고, 예외로 재시도/DLT를 태울 실익도 없다(기록은 영구 사실).
        memberRepository.findByPublicIdAndDeletedAtIsNull(event.userPublicId())
                .ifPresentOrElse(
                        trustGradeService::recalculate,
                        () -> log.warn("마일스톤 기록은 저장했으나 회원이 없어 등급 재계산 스킵. "
                                + "user_public_id={}, event_id={}", event.userPublicId(), event.eventId()));

        log.info("마일스톤 기록 + 등급 재계산 완료. user_public_id={}, milestone_type={}, event_id={}",
                event.userPublicId(), type, event.eventId());
    }

    // ── 현황 조회 (BE-6) ───────────────────────────────────────────────────────

    @Override
    public TrustMilestonesResponse getMyTrustMilestones(String userPublicId) {
        Member member = memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        // 달성 기록을 1회 조회해 유형별 매핑(UNIQUE라 유형당 최대 1건 — 충돌 없음).
        Map<MilestoneType, MemberMilestone> achieved = memberMilestoneRepository
                .findAllByUserPublicId(userPublicId).stream()
                .collect(Collectors.toMap(MemberMilestone::getMilestoneType, Function.identity()));

        // ID_VERIFIED는 members.is_verified로 합성. achieved_at은 최신 APPROVED 인증의 reviewed_at —
        // 레코드가 없으면(관리자 수동 승인 등 verification 상태 전환 미구현 경로) null로 내려보낸다.
        LocalDateTime verifiedAt = member.isVerified()
                ? userVerificationRepository
                        .findTopByMemberAndStatusOrderByIdDesc(member, VerificationStatus.APPROVED)
                        .map(UserVerification::getReviewedAt)
                        .orElse(null)
                : null;

        // ACCOUNT_NINETY_DAYS 합성값 — createdAt 기준 90일 경과 여부 실시간 계산. DB에 저장 안 함.
        boolean ninetyDaysElapsed = member.getCreatedAt() != null
                && java.time.temporal.ChronoUnit.DAYS.between(
                        member.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)) >= 90L;
        LocalDateTime memberCreatedAt = member.getCreatedAt();

        // 카탈로그 전체(미달성 포함) — 순차 체인(Lv2→Lv4) + GOLD 보너스(4종) 순서 고정.
        // 프론트: 체인 3개로 Lv4 TRUSTED까지 CTA를 그리고, 보너스 섹션에서 GOLD 조건을 표시한다.
        List<MilestoneItem> milestones = List.of(
                // ── 순차 체인 (Lv2→Lv3→Lv4) ──────────────────────────────────────────
                MilestoneItem.of(ID_VERIFIED, member.isVerified(), verifiedAt),
                toItem(MilestoneType.BANK_ACCOUNT_CONNECTED, achieved),
                toItem(MilestoneType.FIRST_TRANSACTION_COMPLETED, achieved),
                // ── GOLD 보너스 4종 (2개 이상 달성 시 Lv5 GOLD) ─────────────────────
                toItem(MilestoneType.DOCUMENT_ANALYZED, achieved),
                toItem(MilestoneType.COMMUNITY_ACTIVE, achieved),
                toItem(MilestoneType.TRANSACTION_FIVE_COMPLETED, achieved),
                // ACCOUNT_NINETY_DAYS — DB 저장 없음. 합성: createdAt 90일 경과.
                MilestoneItem.of("ACCOUNT_NINETY_DAYS", ninetyDaysElapsed,
                        ninetyDaysElapsed ? memberCreatedAt : null));

        return TrustMilestonesResponse.of(member.getTrustGrade().name(), milestones);
    }

    private static MilestoneItem toItem(MilestoneType type, Map<MilestoneType, MemberMilestone> achieved) {
        MemberMilestone milestone = achieved.get(type);
        return MilestoneItem.of(type.name(), milestone != null,
                milestone != null ? milestone.getAchievedAt() : null);
    }
}
