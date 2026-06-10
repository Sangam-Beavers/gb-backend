package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import com.gb.member.domain.milestone.repository.MemberMilestoneRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TrustGradeServiceImpl 단위 테스트 (이슈 #193 / Phase 2 BE-5 — 재계산 단일 진입점).
 *
 * <p>Phase 2 산정 매트릭스(인증 × 계좌 연결 × 첫 거래)와 순차 체인 규칙(인증 취소 강등 → 기록 보존
 * 덕에 재인증 시 즉시 복귀)이 "호출 시점의 현재 상태" 기준으로 산정됨을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TrustGradeServiceImplTest {

    private static final String PUBLIC_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private MemberMilestoneRepository memberMilestoneRepository;

    @InjectMocks
    private TrustGradeServiceImpl trustGradeService;

    private Member activeMember() {
        return Member.builder()
                .publicId(PUBLIC_ID)
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

    private MemberMilestone milestone(MilestoneType type) {
        return MemberMilestone.builder()
                .userPublicId(PUBLIC_ID)
                .milestoneType(type)
                .achievedAt(LocalDateTime.of(2026, 6, 10, 5, 21, 8))
                .eventId("f47ac10b-58cc-4372-a567-0e02b2c3d479")
                .build();
    }

    private void givenAchieved(MilestoneType... types) {
        given(memberMilestoneRepository.findAllByUserPublicId(PUBLIC_ID))
                .willReturn(List.of(types).stream().map(this::milestone).toList());
    }

    // ===== 산정 매트릭스 (인증 × 계좌 × 거래) =====

    @Test
    @DisplayName("미인증(is_verified=false)은 NEWCOMER — 마일스톤 조회 자체를 하지 않는다(early return)")
    void recalculate_미인증_NEWCOMER() {
        Member member = activeMember();
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.NEWCOMER); // 가입 직후 inline 기본값

        TrustGrade grade = trustGradeService.recalculate(member);

        assertThat(grade).isEqualTo(TrustGrade.NEWCOMER);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.NEWCOMER);
        verifyNoInteractions(memberMilestoneRepository); // Lv2 관문에서 끊김 — 불필요 쿼리 없음
    }

    @Test
    @DisplayName("인증 + 마일스톤 없음 → VERIFIED")
    void recalculate_인증만_VERIFIED() {
        Member member = activeMember();
        member.markVerified();
        givenAchieved(); // 빈 목록

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.VERIFIED);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.VERIFIED);
    }

    @Test
    @DisplayName("인증 + 계좌 연결 → CONNECTED")
    void recalculate_인증_계좌연결_CONNECTED() {
        Member member = activeMember();
        member.markVerified();
        givenAchieved(MilestoneType.BANK_ACCOUNT_CONNECTED);

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.CONNECTED);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.CONNECTED);
    }

    @Test
    @DisplayName("인증 + 계좌 연결 + 첫 거래 → TRUSTED")
    void recalculate_인증_계좌_거래_TRUSTED() {
        Member member = activeMember();
        member.markVerified();
        givenAchieved(MilestoneType.BANK_ACCOUNT_CONNECTED, MilestoneType.FIRST_TRANSACTION_COMPLETED);

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.TRUSTED);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.TRUSTED);
    }

    @Test
    @DisplayName("순차 체인: 계좌 연결 없이 첫 거래만 있으면 VERIFIED에 머문다(이벤트 도착 순서 역전 방어)")
    void recalculate_거래만_체인위배_VERIFIED() {
        Member member = activeMember();
        member.markVerified();
        givenAchieved(MilestoneType.FIRST_TRANSACTION_COMPLETED);

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.VERIFIED);
    }

    @Test
    @DisplayName("미인증인데 마일스톤을 다 가진 회원도 NEWCOMER — 인증이 Lv2 관문(체인 하한)")
    void recalculate_미인증_마일스톤보유_NEWCOMER() {
        Member member = activeMember(); // is_verified=false

        TrustGrade grade = trustGradeService.recalculate(member);

        assertThat(grade).isEqualTo(TrustGrade.NEWCOMER);
        // early return이라 마일스톤 보유 여부는 결과에 영향 없음(조회 미수행 검증으로 갈음).
        verifyNoInteractions(memberMilestoneRepository);
    }

    // ===== 취소 강등 → 재인증 복귀 (기록 보존 덕에 즉시 CONNECTED+) =====

    @Test
    @DisplayName("TRUSTED 회원이 인증 취소되면 NEWCOMER로 강등, 재인증하면 기록 보존 덕에 즉시 TRUSTED 복귀")
    void recalculate_인증취소_강등_재인증_즉시복귀() {
        // 배지 회수 도메인 메서드는 후속 스프린트 도입 예정(MemberAdminInternalServiceImpl.rejectKyc 주석)이라,
        // mock으로 is_verified 상태 전이(true → false → true)를 모사해 "현재 상태 기준 재계산"을 검증한다.
        Member member = mock(Member.class);
        when(member.isVerified()).thenReturn(true, false, true);
        when(member.getPublicId()).thenReturn(PUBLIC_ID);
        givenAchieved(MilestoneType.BANK_ACCOUNT_CONNECTED, MilestoneType.FIRST_TRANSACTION_COMPLETED);

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.TRUSTED);   // 인증 + 기록 2개
        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.NEWCOMER);  // 취소 → 강등(기록 보존)
        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.TRUSTED);   // 재인증 → 즉시 복귀

        verify(member, times(2)).applyTrustGrade(TrustGrade.TRUSTED);
        verify(member, times(1)).applyTrustGrade(TrustGrade.NEWCOMER);
        // 강등 호출(2번째)에서는 마일스톤 조회가 없다 — 총 조회는 인증 상태였던 2회뿐.
        verify(memberMilestoneRepository, times(2)).findAllByUserPublicId(PUBLIC_ID);
    }
}
