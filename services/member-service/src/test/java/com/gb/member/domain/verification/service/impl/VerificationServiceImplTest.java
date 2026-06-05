package com.gb.member.domain.verification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.verification.dto.request.VerificationRequest;
import com.gb.member.domain.verification.dto.response.VerificationStatusResponse;
import com.gb.member.domain.verification.dto.response.VerificationSubmitResponse;
import com.gb.member.domain.verification.entity.IdentityDocumentType;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import com.gb.member.domain.verification.repository.UserVerificationRepository;
import com.gb.member.global.client.WalletClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import org.mockito.Mockito;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * VerificationServiceImpl 단위 테스트.
 *
 * <p>실 신원확인 대신 유형별 번호 형식(정규식) 검증으로 처리한다. 주 신분증인 외국인등록증의 형식 통과 시
 * 즉시 승인 + 인증 배지 부여, 형식 불일치/잘못된 유형은 COMMON4001, 중복 제출은 COMMON4091,
 * 없는 회원/이력 없음은 MEMBER4001임을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private UserVerificationRepository verificationRepository;
    /** 이슈 #152 — APPROVED 시 자동 지갑 개설 위임. fail-open 동작 검증을 위해 mock 주입. */
    @Mock private WalletClient walletClient;

    @InjectMocks private VerificationServiceImpl verificationService;

    @BeforeEach
    void injectSelf() {
        // 생성자 주입(@RequiredArgsConstructor)에선 @InjectMocks가 비-final self 필드를 채우지 않아 null.
        // 단위 테스트는 프록시 없이 service 자신을 박아 submitVerification→submitVerificationTx 위임 체인을
        // 그대로 탄다(@Transactional은 단위 테스트에서 no-op — community CommentServiceTest와 동일 처리).
        ReflectionTestUtils.setField(verificationService, "self", verificationService);
    }

    private static final String PUBLIC_ID = "11111111-1111-1111-1111-111111111111";
    private static final String VALID_ARC = "990101-5678901";   // 외국인등록번호: 뒤 첫자리 5(외국인 5~8)

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
                .consentAgreedAt(java.time.LocalDateTime.now())
                .build();
    }

    private VerificationRequest request(String type, String number, String s3Key) {
        VerificationRequest request = new VerificationRequest();
        ReflectionTestUtils.setField(request, "identityDocumentType", type);
        ReflectionTestUtils.setField(request, "documentNumber", number);
        ReflectionTestUtils.setField(request, "s3Key", s3Key);
        return request;
    }

    // ───────────────────────── 상태 조회 ─────────────────────────

    @Test
    @DisplayName("인증 상태 조회는 회원의 최근 인증 1건을 반환한다")
    void getMyVerification_성공() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));

        UserVerification verification = UserVerification.approved(
                member, IdentityDocumentType.ALIEN_REGISTRATION, VALID_ARC, "verifications/x/front.jpg");
        ReflectionTestUtils.setField(verification, "createdAt", LocalDateTime.of(2026, 5, 20, 9, 0, 0));
        when(verificationRepository.findTopByMemberOrderByIdDesc(member)).thenReturn(Optional.of(verification));

        VerificationStatusResponse response = verificationService.getMyVerification(PUBLIC_ID);

        assertThat(response.getIdentityDocumentType()).isEqualTo("ALIEN_REGISTRATION");
        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getReviewedAt()).isNotNull();
        assertThat(response.getCreatedAt()).isEqualTo("2026-05-20T09:00:00Z");
    }

    @Test
    @DisplayName("없는(탈퇴 포함) 회원이면 MEMBER4001, 인증 테이블은 보지 않는다")
    void getMyVerification_회원없음_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getMyVerification(PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(verificationRepository);
    }

    @Test
    @DisplayName("인증 이력이 없으면 MEMBER4001로 처리한다")
    void getMyVerification_이력없음_MEMBER4001() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.findTopByMemberOrderByIdDesc(member)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getMyVerification(PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    // ───────────────────────── 인증 요청 ─────────────────────────

    @Test
    @DisplayName("외국인등록번호 형식이 맞으면 즉시 승인하고 인증 배지를 부여한다 + 지갑 자동 개설 위임")
    void submit_외국인등록증_성공_즉시승인_배지부여() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");

        VerificationSubmitResponse response = verificationService.submitVerification(PUBLIC_ID, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(member.isVerified()).isTrue();   // 배지 부여(dirty checking 대상)

        ArgumentCaptor<UserVerification> saved = ArgumentCaptor.forClass(UserVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDocumentType()).isEqualTo(IdentityDocumentType.ALIEN_REGISTRATION);
        assertThat(saved.getValue().getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(saved.getValue().getReviewedAt()).isNotNull();
        assertThat(saved.getValue().getDocumentNumber()).isEqualTo(VALID_ARC);

        // 이슈 #152 — APPROVED 시 wallet-service에 지갑 자동 개설 위임 (멱등 호출).
        verify(walletClient).createWalletFor(PUBLIC_ID);
    }

    @Test
    @DisplayName("s3_key가 null이어도 형식 검증 통과 시 즉시 승인된다(OCR 미도입 데모 정책)")
    void submit_s3key_null_허용_성공() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        // s3Key를 null로 전송 — VerificationRequest의 @NotBlank가 제거됐으므로 통과해야 한다(#152).
        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, null);

        VerificationSubmitResponse response = verificationService.submitVerification(PUBLIC_ID, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(member.isVerified()).isTrue();

        ArgumentCaptor<UserVerification> saved = ArgumentCaptor.forClass(UserVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getS3Key()).isNull();   // 그대로 null 저장

        verify(walletClient).createWalletFor(PUBLIC_ID);
    }

    @Test
    @DisplayName("지갑 자동 개설 호출이 실패해도 인증은 통과한다(fail-open) — 이슈 #152")
    void submit_지갑개설_호출실패_failOpen() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);
        // wallet-service 호출이 예외를 던지더라도 인증은 정상 commit 돼야 한다.
        Mockito.doThrow(new RuntimeException("wallet-service unreachable"))
                .when(walletClient).createWalletFor(PUBLIC_ID);

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");

        VerificationSubmitResponse response = verificationService.submitVerification(PUBLIC_ID, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(member.isVerified()).isTrue();
        verify(verificationRepository).saveAndFlush(any());     // 인증은 저장됨(10D 백스톱 — saveAndFlush)
        verify(walletClient).createWalletFor(PUBLIC_ID);   // 호출 자체는 시도됐음
    }

    @Test
    @DisplayName("hoist 회귀: 지갑 개설(외부 HTTP)은 인증 DB 본문(saveAndFlush) '뒤' — tx 메서드 밖 호출 순서 고정")
    void submit_지갑개설은_본문_커밋_후() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");
        verificationService.submitVerification(PUBLIC_ID, request);

        // submitVerificationTx(검증·저장·배지)가 끝난 "다음" walletClient를 호출해야 한다 — 운영에선 이 순서가
        // "tx 커밋 후 외부 HTTP"를 의미한다(외부 호출이 쓰기 tx·커넥션을 잡지 않음, community createComment와 동일).
        InOrder order = Mockito.inOrder(verificationRepository, walletClient);
        order.verify(verificationRepository).saveAndFlush(any());
        order.verify(walletClient).createWalletFor(PUBLIC_ID);
    }

    @Test
    @DisplayName("외국인등록번호 형식이 틀리면 COMMON4001, 저장·배지부여 없음")
    void submit_형식불일치_COMMON4001() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        // 뒤 첫자리가 1 → 외국인(5~8) 아님 → 형식 불일치
        VerificationRequest request = request("ALIEN_REGISTRATION", "990101-1234567", "verifications/x/front.jpg");

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(verificationRepository, never()).saveAndFlush(any());
        assertThat(member.isVerified()).isFalse();
        verifyNoInteractions(walletClient);   // APPROVED 실패 → 지갑 생성 시도 없음(#152)
    }

    @Test
    @DisplayName("지원하지 않는 신분증 유형(여권 등 제거된 코드 포함)이면 COMMON4001")
    void submit_잘못된유형_COMMON4001() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        // PASSPORT/NATIONAL_ID는 이슈 #108에서 제거됨 — 더 이상 enum에 없으므로 거절돼야 한다.
        VerificationRequest request = request("PASSPORT", "M12345678", "verifications/x/front.jpg");

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(verificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("베트남 CCCD 12자리 통과 시 즉시 승인 + 지갑 자동 개설(이슈 #108)")
    void submit_베트남CCCD_성공() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        VerificationRequest request = request("NATIONAL_ID_VN", "079199012345", null);

        VerificationSubmitResponse response = verificationService.submitVerification(PUBLIC_ID, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(member.isVerified()).isTrue();
        verify(walletClient).createWalletFor(PUBLIC_ID);

        ArgumentCaptor<UserVerification> saved = ArgumentCaptor.forClass(UserVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDocumentNumber()).isEqualTo("079199012345");
    }

    @Test
    @DisplayName("필리핀 PCN은 소문자로 입력해도 대문자로 정규화돼 저장된다(이슈 #108)")
    void submit_필리핀PCN_대문자정규화() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        // 16자리 영숫자, 일부 소문자 포함 — 정규화 후 매칭 + 저장도 대문자.
        VerificationRequest request = request("NATIONAL_ID_PH", "a1b2c3d4e5f6g7h8", null);

        VerificationSubmitResponse response = verificationService.submitVerification(PUBLIC_ID, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");

        ArgumentCaptor<UserVerification> saved = ArgumentCaptor.forClass(UserVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDocumentNumber()).isEqualTo("A1B2C3D4E5F6G7H8");
    }

    @Test
    @DisplayName("미국 SSN 영역코드 666 시작은 형식 위반(엄격 정규식) → COMMON4001")
    void submit_미국SSN_금지영역코드_COMMON4001() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);

        VerificationRequest request = request("NATIONAL_ID_US", "666-12-3456", null);

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(verificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 진행중/승인된 인증이 있으면 COMMON4091로 중복 거절")
    void submit_중복_COMMON4091() {
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(true);

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        verify(verificationRepository, never()).saveAndFlush(any());
        assertThat(member.isVerified()).isFalse();
        verifyNoInteractions(walletClient);   // 중복 거절 → 지갑 생성 시도 없음(#152)
    }

    @Test
    @DisplayName("동시 제출 race: existsBy 통과 후 saveAndFlush가 무결성 위반을 던지면 COMMON4091, 배지 미부여 (10D member-verification-1)")
    void submit_동시요청_race_saveAndFlush_무결성위반_COMMON4091() {
        // 자기 동시요청 둘이 모두 existsBy=false를 통과한 race(TOCTOU). 활성 인증 부분 UNIQUE(별도 DDL) 위반이
        // saveAndFlush에서 터지면 가입(member-5)과 동일하게 generic COMMON4091로 변환되고, markVerified()에
        // 도달하지 않아 배지 중복 부여도 차단된다(MemberServiceImplTest WU-F8과 동일 패턴).
        Member member = activeMember();
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(member));
        when(verificationRepository.existsByMemberAndStatusIn(eq(member), anyCollection())).thenReturn(false);
        when(verificationRepository.saveAndFlush(any(UserVerification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_user_verifications_active"));

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        assertThat(member.isVerified()).as("race 패자는 배지를 받지 않는다").isFalse();
    }

    @Test
    @DisplayName("제출 시 없는 회원이면 MEMBER4001, 인증 테이블은 보지 않는다")
    void submit_회원없음_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.empty());

        VerificationRequest request = request("ALIEN_REGISTRATION", VALID_ARC, "verifications/x/front.jpg");

        assertThatThrownBy(() -> verificationService.submitVerification(PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(verificationRepository);
    }
}
