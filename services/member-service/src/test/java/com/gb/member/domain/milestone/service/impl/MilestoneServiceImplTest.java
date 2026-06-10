package com.gb.member.domain.milestone.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.TrustGradeService;
import com.gb.member.domain.milestone.dto.event.MilestoneAchievedEvent;
import com.gb.member.domain.milestone.dto.response.TrustMilestonesResponse;
import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import com.gb.member.domain.milestone.repository.MemberMilestoneRepository;
import com.gb.member.domain.verification.entity.IdentityDocumentType;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import com.gb.member.domain.verification.repository.UserVerificationRepository;
import com.gb.member.global.exception.code.MemberErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MilestoneServiceImpl 단위 테스트 (Phase 2 — BE-4 수신 기록 멱등/재계산, BE-6 현황 조회).
 *
 * <p>수신 기록: 신규 INSERT + 같은 tx 재계산 / 중복(자연 멱등) 스킵 / 미지 milestone_type 스킵(전방 호환)
 * / 회원 부재 시 기록만 저장. 현황 조회: 단계별 유저(가입만/인증만/계좌까지/거래까지) 응답 검증.
 */
@ExtendWith(MockitoExtension.class)
class MilestoneServiceImplTest {

    private static final String USER = "9b2f1111-2222-3333-4444-555566667777";
    private static final String EVENT_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final Instant OCCURRED_AT = Instant.parse("2026-06-10T05:21:08Z");

    @Mock private MemberMilestoneRepository memberMilestoneRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private UserVerificationRepository userVerificationRepository;
    @Mock private TrustGradeService trustGradeService;
    @InjectMocks private MilestoneServiceImpl milestoneService;

    private static MilestoneAchievedEvent event(String milestoneType) {
        return new MilestoneAchievedEvent(EVENT_ID, "MILESTONE_ACHIEVED", milestoneType,
                USER, OCCURRED_AT, "wallet-service", 1);
    }

    private Member member() {
        return Member.builder()
                .publicId(USER)
                .email("nguyen@example.com")
                .name("Nguyen")
                .nickname("하노이댁")
                .nationality("VN")
                .language("vi")
                .authProviderId("idp-sub")
                .termsAgreed(true)
                .privacyAgreed(true)
                .consentAgreedAt(LocalDateTime.now())
                .build();
    }

    private static MemberMilestone milestone(MilestoneType type, LocalDateTime achievedAt) {
        return MemberMilestone.builder()
                .userPublicId(USER)
                .milestoneType(type)
                .achievedAt(achievedAt)
                .eventId(EVENT_ID)
                .build();
    }

    // ===== recordMilestone (BE-4) =====

    @Test
    @DisplayName("신규 수신: 기록 INSERT(occurred_at 스냅샷 보존) 후 같은 흐름에서 등급 재계산 호출")
    void recordMilestone_신규_INSERT_후_재계산() {
        Member member = member();
        given(memberMilestoneRepository.existsByUserPublicIdAndMilestoneType(
                USER, MilestoneType.BANK_ACCOUNT_CONNECTED)).willReturn(false);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));

        milestoneService.recordMilestone(event("BANK_ACCOUNT_CONNECTED"));

        ArgumentCaptor<MemberMilestone> captor = ArgumentCaptor.forClass(MemberMilestone.class);
        verify(memberMilestoneRepository).save(captor.capture());
        MemberMilestone saved = captor.getValue();
        assertThat(saved.getUserPublicId()).isEqualTo(USER);
        assertThat(saved.getMilestoneType()).isEqualTo(MilestoneType.BANK_ACCOUNT_CONNECTED);
        assertThat(saved.getAchievedAt())
                .as("achieved_at은 발행측 occurred_at(UTC) 스냅샷")
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 21, 8));
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);

        verify(trustGradeService).recalculate(member);
    }

    @Test
    @DisplayName("중복 수신(자연 멱등): 이미 기록된 (user, milestone)이면 INSERT/재계산 없이 조용히 스킵")
    void recordMilestone_중복_스킵() {
        given(memberMilestoneRepository.existsByUserPublicIdAndMilestoneType(
                USER, MilestoneType.FIRST_TRANSACTION_COMPLETED)).willReturn(true);

        milestoneService.recordMilestone(event("FIRST_TRANSACTION_COMPLETED"));

        verify(memberMilestoneRepository, never()).save(any());
        verifyNoInteractions(memberRepository, trustGradeService);
    }

    @Test
    @DisplayName("미지의 milestone_type(추후 확장 값): WARN 스킵 — 저장/조회/재계산 전부 미수행(전방 호환)")
    void recordMilestone_미지타입_스킵() {
        milestoneService.recordMilestone(event("COMMUNITY_SUPERSTAR"));

        verifyNoInteractions(memberMilestoneRepository, memberRepository, trustGradeService);
    }

    @Test
    @DisplayName("회원 부재(탈퇴 등): 기록은 저장하되 등급 재계산만 스킵(WARN)")
    void recordMilestone_회원부재_기록만_저장() {
        given(memberMilestoneRepository.existsByUserPublicIdAndMilestoneType(
                USER, MilestoneType.BANK_ACCOUNT_CONNECTED)).willReturn(false);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.empty());

        milestoneService.recordMilestone(event("BANK_ACCOUNT_CONNECTED"));

        verify(memberMilestoneRepository).save(any(MemberMilestone.class));
        verifyNoInteractions(trustGradeService);
    }

    @Test
    @DisplayName("occurred_at 누락 이벤트: 수신 시각(UTC)으로 폴백해 NOT NULL 컬럼을 보장")
    void recordMilestone_occurredAt_누락_폴백() {
        given(memberMilestoneRepository.existsByUserPublicIdAndMilestoneType(
                USER, MilestoneType.BANK_ACCOUNT_CONNECTED)).willReturn(false);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member()));

        milestoneService.recordMilestone(new MilestoneAchievedEvent(
                EVENT_ID, "MILESTONE_ACHIEVED", "BANK_ACCOUNT_CONNECTED", USER, null, "wallet-service", 1));

        ArgumentCaptor<MemberMilestone> captor = ArgumentCaptor.forClass(MemberMilestone.class);
        verify(memberMilestoneRepository).save(captor.capture());
        assertThat(captor.getValue().getAchievedAt()).isNotNull();
    }

    // ===== getMyTrustMilestones (BE-6) — 단계별 유저 =====

    @Test
    @DisplayName("가입만(미인증): NEWCOMER + 카탈로그 3종 전부 미달성(achieved_at null), 인증 시각 조회 안 함")
    void getMyTrustMilestones_가입만() {
        Member member = member(); // is_verified=false, trust_grade=NEWCOMER(기본)
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));
        given(memberMilestoneRepository.findAllByUserPublicId(USER)).willReturn(List.of());

        TrustMilestonesResponse response = milestoneService.getMyTrustMilestones(USER);

        assertThat(response.getTrustGrade()).isEqualTo("NEWCOMER");
        assertThat(response.getMilestones())
                .extracting(m -> m.getMilestoneType(), m -> m.getAchieved(), m -> m.getAchievedAt())
                .containsExactly(
                        tuple("ID_VERIFIED", false, null),
                        tuple("BANK_ACCOUNT_CONNECTED", false, null),
                        tuple("FIRST_TRANSACTION_COMPLETED", false, null));
        verifyNoInteractions(userVerificationRepository); // 미인증이면 승인 시각 조회 불필요
    }

    @Test
    @DisplayName("인증만: VERIFIED + ID_VERIFIED만 달성(achieved_at = APPROVED reviewed_at)")
    void getMyTrustMilestones_인증만() {
        Member member = member();
        member.markVerified();
        member.applyTrustGrade(TrustGrade.VERIFIED);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));
        given(memberMilestoneRepository.findAllByUserPublicId(USER)).willReturn(List.of());
        UserVerification verification = UserVerification.builder()
                .member(member)
                .documentType(IdentityDocumentType.ALIEN_REGISTRATION)
                .documentNumber("990101-5678901")
                .status(VerificationStatus.APPROVED)
                .reviewedAt(LocalDateTime.of(2026, 6, 1, 10, 0, 0))
                .build();
        given(userVerificationRepository.findTopByMemberAndStatusOrderByIdDesc(member, VerificationStatus.APPROVED))
                .willReturn(Optional.of(verification));

        TrustMilestonesResponse response = milestoneService.getMyTrustMilestones(USER);

        assertThat(response.getTrustGrade()).isEqualTo("VERIFIED");
        assertThat(response.getMilestones())
                .extracting(m -> m.getMilestoneType(), m -> m.getAchieved(), m -> m.getAchievedAt())
                .containsExactly(
                        tuple("ID_VERIFIED", true, "2026-06-01T10:00:00Z"),
                        tuple("BANK_ACCOUNT_CONNECTED", false, null),
                        tuple("FIRST_TRANSACTION_COMPLETED", false, null));
    }

    @Test
    @DisplayName("인증인데 APPROVED 인증 레코드가 없으면(관리자 수동 승인 경로) ID_VERIFIED는 달성 + achieved_at=null")
    void getMyTrustMilestones_인증_레코드없음_achievedAt_null() {
        Member member = member();
        member.markVerified();
        member.applyTrustGrade(TrustGrade.VERIFIED);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));
        given(memberMilestoneRepository.findAllByUserPublicId(USER)).willReturn(List.of());
        given(userVerificationRepository.findTopByMemberAndStatusOrderByIdDesc(member, VerificationStatus.APPROVED))
                .willReturn(Optional.empty());

        TrustMilestonesResponse response = milestoneService.getMyTrustMilestones(USER);

        assertThat(response.getMilestones().get(0).getMilestoneType()).isEqualTo("ID_VERIFIED");
        assertThat(response.getMilestones().get(0).getAchieved()).isTrue();
        assertThat(response.getMilestones().get(0).getAchievedAt()).isNull();
    }

    @Test
    @DisplayName("계좌까지: CONNECTED + ID_VERIFIED·BANK_ACCOUNT_CONNECTED 달성, 첫 거래만 미달성")
    void getMyTrustMilestones_계좌까지() {
        Member member = member();
        member.markVerified();
        member.applyTrustGrade(TrustGrade.CONNECTED);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));
        given(memberMilestoneRepository.findAllByUserPublicId(USER)).willReturn(List.of(
                milestone(MilestoneType.BANK_ACCOUNT_CONNECTED, LocalDateTime.of(2026, 6, 10, 5, 21, 8))));
        given(userVerificationRepository.findTopByMemberAndStatusOrderByIdDesc(member, VerificationStatus.APPROVED))
                .willReturn(Optional.empty());

        TrustMilestonesResponse response = milestoneService.getMyTrustMilestones(USER);

        assertThat(response.getTrustGrade()).isEqualTo("CONNECTED");
        assertThat(response.getMilestones())
                .extracting(m -> m.getMilestoneType(), m -> m.getAchieved(), m -> m.getAchievedAt())
                .containsExactly(
                        tuple("ID_VERIFIED", true, null),
                        tuple("BANK_ACCOUNT_CONNECTED", true, "2026-06-10T05:21:08Z"),
                        tuple("FIRST_TRANSACTION_COMPLETED", false, null));
    }

    @Test
    @DisplayName("거래까지: TRUSTED + 카탈로그 3종 전부 달성")
    void getMyTrustMilestones_거래까지() {
        Member member = member();
        member.markVerified();
        member.applyTrustGrade(TrustGrade.TRUSTED);
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.of(member));
        given(memberMilestoneRepository.findAllByUserPublicId(USER)).willReturn(List.of(
                milestone(MilestoneType.BANK_ACCOUNT_CONNECTED, LocalDateTime.of(2026, 6, 10, 5, 21, 8)),
                milestone(MilestoneType.FIRST_TRANSACTION_COMPLETED, LocalDateTime.of(2026, 6, 11, 9, 0, 0))));
        given(userVerificationRepository.findTopByMemberAndStatusOrderByIdDesc(member, VerificationStatus.APPROVED))
                .willReturn(Optional.empty());

        TrustMilestonesResponse response = milestoneService.getMyTrustMilestones(USER);

        assertThat(response.getTrustGrade()).isEqualTo("TRUSTED");
        assertThat(response.getMilestones())
                .extracting(m -> m.getMilestoneType(), m -> m.getAchieved(), m -> m.getAchievedAt())
                .containsExactly(
                        tuple("ID_VERIFIED", true, null),
                        tuple("BANK_ACCOUNT_CONNECTED", true, "2026-06-10T05:21:08Z"),
                        tuple("FIRST_TRANSACTION_COMPLETED", true, "2026-06-11T09:00:00Z"));
    }

    @Test
    @DisplayName("회원 없음(탈퇴 포함) → MEMBER4001")
    void getMyTrustMilestones_회원없음_MEMBER4001() {
        given(memberRepository.findByPublicIdAndDeletedAtIsNull(USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> milestoneService.getMyTrustMilestones(USER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        verifyNoInteractions(memberMilestoneRepository, userVerificationRepository);
    }
}
