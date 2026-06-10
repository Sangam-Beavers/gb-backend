package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TrustGradeServiceImpl 단위 테스트 (이슈 #193 — 마일스톤 기반 신뢰등급 재계산 단일 진입점).
 *
 * <p>Phase 1 규칙(is_verified=true → VERIFIED, 아니면 NEWCOMER)이 "호출 시점의 현재 상태" 기준으로
 * 산정됨을 검증한다 — 승인 취소(배지 회수) 시에도 같은 메서드 호출만으로 NEWCOMER 복귀가 된다.
 */
@ExtendWith(MockitoExtension.class)
class TrustGradeServiceImplTest {

    private final TrustGradeServiceImpl trustGradeService = new TrustGradeServiceImpl();

    private Member activeMember() {
        return Member.builder()
                .publicId("11111111-1111-1111-1111-111111111111")
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

    @Test
    @DisplayName("인증 전(is_verified=false) 회원은 NEWCOMER로 산정된다 — 가입 직후 기본값과 일치")
    void recalculate_인증전_NEWCOMER() {
        Member member = activeMember();
        // 가입 직후 엔티티 inline 기본값도 NEWCOMER(재계산 전에도 등급이 비어 있지 않다).
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.NEWCOMER);

        TrustGrade grade = trustGradeService.recalculate(member);

        assertThat(grade).isEqualTo(TrustGrade.NEWCOMER);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.NEWCOMER);
    }

    @Test
    @DisplayName("신분증 인증 승인(is_verified=true) 후 재계산하면 VERIFIED로 상향된다")
    void recalculate_인증승인후_VERIFIED() {
        Member member = activeMember();
        member.markVerified();

        TrustGrade grade = trustGradeService.recalculate(member);

        assertThat(grade).isEqualTo(TrustGrade.VERIFIED);
        assertThat(member.getTrustGrade()).isEqualTo(TrustGrade.VERIFIED);
    }

    @Test
    @DisplayName("인증 취소로 is_verified=false가 되면 같은 재계산 호출로 NEWCOMER로 복귀한다")
    void recalculate_인증취소_NEWCOMER_복귀() {
        // 배지 회수 도메인 메서드는 후속 스프린트 도입 예정(MemberAdminInternalServiceImpl.rejectKyc 주석 참고)이라,
        // mock으로 is_verified 상태 전이(true → false)를 모사해 "현재 상태 기준 재계산" 규칙을 검증한다.
        Member member = mock(Member.class);
        when(member.isVerified()).thenReturn(true, false);

        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.VERIFIED);
        assertThat(trustGradeService.recalculate(member)).isEqualTo(TrustGrade.NEWCOMER);

        verify(member, times(1)).applyTrustGrade(TrustGrade.VERIFIED);
        verify(member, times(1)).applyTrustGrade(TrustGrade.NEWCOMER);
    }
}
