package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.domain.member.dto.request.PasswordResetEmailRequest;
import com.gb.member.domain.member.dto.request.PasswordResetRequest;
import com.gb.member.domain.member.dto.request.ProfileUpdateRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.request.SocialProfileRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.LanguageResponse;
import com.gb.member.domain.member.dto.response.ProfileResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.dto.response.SocialProfileResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.mail.EmailSender;
import com.gb.member.global.redis.PasswordResetRateLimiter;
import com.gb.member.global.redis.PasswordResetTokenStore;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MemberServiceImpl 단위 테스트.
 *
 * <p>방식 B(검증 전용): 로그인은 프론트가 IdP와 직접(Authorization Code flow) 수행하므로 백엔드 서비스엔
 * login()이 없다. 외부 IdP 회원 등록은 {@link IdpUserClient}를 mock해 대체한다.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>회원가입 — IdP에 사용자를 등록하고 받은 sub를 authProviderId에 저장, 비밀번호 미저장, 중복 거절</li>
 *   <li>이메일/닉네임 중복 확인 — existsBy 결과의 반전(존재→사용불가, 없음→사용가능). 조회만 하므로 IdP를 건드리지 않음</li>
 *   <li>비밀번호 재설정 — 가입 이메일만 발송(가입여부 비노출), 토큰 검증/IdP 변경/토큰 삭제</li>
 *   <li>언어 조회/변경 · 탈퇴 — 활성 회원 조회(soft delete 제외), dirty checking, IdP 비활성화</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private IdpUserClient idpUserClient;
    @Mock private PasswordResetTokenStore passwordResetTokenStore;
    @Mock private PasswordResetRateLimiter passwordResetRateLimiter;
    @Mock private EmailSender emailSender;

    @InjectMocks private MemberServiceImpl memberService;

    // ───────────────────────── 회원가입 ─────────────────────────

    @Test
    @DisplayName("회원가입은 IdP에 사용자를 등록하고 받은 sub를 authProviderId에 저장한 회원을 만든다(비번 미저장)")
    void signup_성공_IdP등록_비밀번호_미저장() {
        // given
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "new@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");

        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        // MEM-02 — 로컬 row를 IdP 호출 전에 먼저 선점(saveAndFlush). publicId는 Service가 채워 넘기므로 그대로 돌려준다.
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // IdP가 사용자를 만들고 식별자(sub=uuid)를 돌려준다. publicId는 Service가 만들어 4번째 인자로 넘긴다.
        when(idpUserClient.provisionUser(eq("new@example.com"), eq("홍길동"), eq("P@ssw0rd!"), anyString()))
                .thenReturn("idp-sub-uuid-9999");

        // when
        SignupResponse response = memberService.signup(request);

        // then
        assertThat(response.getEmail()).isEqualTo("new@example.com");
        assertThat(response.getNickname()).isEqualTo("gildong");

        // publicId가 IdP(attributes)와 우리 회원에 "같은 값"으로 들어가야 한다(토큰 sub ↔ publicId 매핑의 핵심).
        ArgumentCaptor<String> idpPublicId = ArgumentCaptor.forClass(String.class);
        verify(idpUserClient).provisionUser(eq("new@example.com"), eq("홍길동"), eq("P@ssw0rd!"), idpPublicId.capture());

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPublicId()).isEqualTo(idpPublicId.getValue());
        // 응답 publicId도 동일해야 한다.
        assertThat(response.getPublicId()).isEqualTo(idpPublicId.getValue());
        // IdP가 준 sub가 assignAuthProviderId로 회원에 채워져야 한다(같은 인스턴스라 캡처 후에도 반영됨).
        assertThat(saved.getValue().getAuthProviderId()).isEqualTo("idp-sub-uuid-9999");

        // MEM-02 회귀: 로컬 선점(saveAndFlush)이 IdP provision보다 *먼저* 실행돼야 한다(IdP 고아계정 방지의 핵심).
        InOrder order = inOrder(memberRepository, idpUserClient);
        order.verify(memberRepository).saveAndFlush(any(Member.class));
        order.verify(idpUserClient).provisionUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MEM-02: provision 실패 시 예외 전파 + 로컬 선점(saveAndFlush)이 provision보다 먼저(실패 시 tx 롤백으로 로컬도 제거 → 고아 방지)")
    void signup_provision실패_고아방지_순서() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "new@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");

        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idpUserClient.provisionUser(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("IdP unavailable"));

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(RuntimeException.class);

        // 로컬 선점이 provision보다 먼저였음을 보장한다 — provision 실패 시 @Transactional 롤백으로 ①에서 만든
        // 로컬 row도 사라진다(로컬·IdP 모두 없음). 단위 테스트는 tx 경계 밖이라 "순서"를 회귀로 고정한다.
        InOrder order = inOrder(memberRepository, idpUserClient);
        order.verify(memberRepository).saveAndFlush(any(Member.class));
        order.verify(idpUserClient).provisionUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이메일 중복이면 MEMBER4002로 거절하고 저장하지 않는다")
    void signup_이메일중복_MEMBER4002() {
        // given
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "dup@example.com");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        when(memberRepository.existsByEmail("dup@example.com")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.EMAIL_ALREADY_EXISTS);

        verify(memberRepository, never()).saveAndFlush(any());
        // 중복이면 IdP에도 사용자를 만들지 않는다(로컬 검증이 먼저).
        verify(idpUserClient, never()).provisionUser(any(), any(), any(), any());
    }

    // ──────────────────── 소셜 가입 추가정보 보완 ────────────────────

    private SocialProfileRequest socialProfileRequest(String nickname, String nationality, String language) {
        SocialProfileRequest request = new SocialProfileRequest();
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ReflectionTestUtils.setField(request, "nationality", nationality);
        ReflectionTestUtils.setField(request, "language", language);
        return request;
    }

    @Test
    @DisplayName("소셜 보완: 토큰 claim(publicId/email/name/sub)+입력으로 members row를 최초 생성한다")
    void completeSocialProfile_성공_row생성() {
        // given — 소셜 신규회원: 토큰은 있으나 members row 없음(미완료)
        SocialProfileRequest request = socialProfileRequest("gildong", "VN", "vi");
        when(memberRepository.existsByPublicId("pub-uuid-1")).thenReturn(false);
        when(memberRepository.existsByEmail("google@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SocialProfileResponse response = memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request);

        // then — 응답 확인
        assertThat(response.getPublicId()).isEqualTo("pub-uuid-1");
        assertThat(response.getEmail()).isEqualTo("google@example.com");
        assertThat(response.getNickname()).isEqualTo("gildong");

        // 저장된 회원: 토큰 claim은 토큰값, 나머지는 입력값으로 채워져야 한다.
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        Member m = saved.getValue();
        assertThat(m.getPublicId()).isEqualTo("pub-uuid-1");
        assertThat(m.getEmail()).isEqualTo("google@example.com");
        assertThat(m.getName()).isEqualTo("홍길동");
        assertThat(m.getAuthProviderId()).isEqualTo("idp-sub-1");
        assertThat(m.getNickname()).isEqualTo("gildong");
        assertThat(m.getNationality()).isEqualTo("VN");
        assertThat(m.getLanguage()).isEqualTo("vi");
        // 소셜 보완은 IdP에 사용자를 새로 만들지 않는다(이미 IdP에 존재).
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("소셜 보완: 이미 완료된 회원(row 존재)이면 COMMON4091로 거절하고 저장하지 않는다")
    void completeSocialProfile_이미존재_COMMON4091() {
        SocialProfileRequest request = socialProfileRequest("gildong", "VN", "vi");
        when(memberRepository.existsByPublicId("pub-uuid-1")).thenReturn(true);

        assertThatThrownBy(() -> memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("소셜 보완: 이메일 중복(다른 계정 사용)이면 MEMBER4002로 거절한다")
    void completeSocialProfile_이메일중복_MEMBER4002() {
        SocialProfileRequest request = socialProfileRequest("gildong", "VN", "vi");
        when(memberRepository.existsByPublicId("pub-uuid-1")).thenReturn(false);
        when(memberRepository.existsByEmail("google@example.com")).thenReturn(true);

        assertThatThrownBy(() -> memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.EMAIL_ALREADY_EXISTS);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("소셜 보완: 닉네임 중복이면 MEMBER4003으로 거절한다")
    void completeSocialProfile_닉네임중복_MEMBER4003() {
        SocialProfileRequest request = socialProfileRequest("dupNick", "VN", "vi");
        when(memberRepository.existsByPublicId("pub-uuid-1")).thenReturn(false);
        when(memberRepository.existsByEmail("google@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("dupNick")).thenReturn(true);

        assertThatThrownBy(() -> memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.NICKNAME_ALREADY_EXISTS);

        verify(memberRepository, never()).save(any());
    }

    // ──────────────────── 이메일/닉네임 중복 확인 ────────────────────

    @Test
    @DisplayName("이메일이 없으면 사용 가능(available=true)을 반환한다")
    void checkEmail_사용가능() {
        when(memberRepository.existsByEmail("free@example.com")).thenReturn(false);

        CheckAvailabilityResponse response = memberService.checkEmail("free@example.com");

        assertThat(response.isAvailable()).isTrue();
        // 중복 확인은 조회만 — IdP를 건드리지 않아야 한다.
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("이메일이 이미 있으면 사용 불가(available=false)를 반환한다")
    void checkEmail_중복() {
        when(memberRepository.existsByEmail("dup@example.com")).thenReturn(true);

        CheckAvailabilityResponse response = memberService.checkEmail("dup@example.com");

        assertThat(response.isAvailable()).isFalse();
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("닉네임이 없으면 사용 가능(available=true)을 반환한다")
    void checkNickname_사용가능() {
        when(memberRepository.existsByNickname("freeNick")).thenReturn(false);

        CheckAvailabilityResponse response = memberService.checkNickname("freeNick");

        assertThat(response.isAvailable()).isTrue();
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("닉네임이 이미 있으면 사용 불가(available=false)를 반환한다")
    void checkNickname_중복() {
        when(memberRepository.existsByNickname("dupNick")).thenReturn(true);

        CheckAvailabilityResponse response = memberService.checkNickname("dupNick");

        assertThat(response.isAvailable()).isFalse();
        verifyNoInteractions(idpUserClient);
    }

    // ──────────────────── 비밀번호 재설정 ────────────────────

    @Test
    @DisplayName("재설정 메일: 가입된 이메일이면 토큰 저장 + 메일 발송(MEM-08: 본문 유효시간이 TTL 단일출처와 일치)")
    void sendPasswordResetEmail_가입됨_발송() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "user@example.com");
        when(passwordResetRateLimiter.tryAcquire("user@example.com")).thenReturn(true);
        when(memberRepository.existsByEmail("user@example.com")).thenReturn(true);
        when(passwordResetTokenStore.ttlMinutes()).thenReturn(30L);

        memberService.sendPasswordResetEmail(request);

        verify(passwordResetTokenStore).save(anyString(), eq("user@example.com"));
        // MEM-08: 본문 "N분 내 유효"의 N이 TTL 단일출처(ttlMinutes)에서 와야 한다(리터럴 분리 방지).
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("user@example.com"), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("30분 내 유효");
    }

    @Test
    @DisplayName("재설정 메일: 미가입 이메일이면 조용히 종료(토큰/메일 없음 — 가입여부 노출 방지)")
    void sendPasswordResetEmail_미가입_조용히종료() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "nobody@example.com");
        when(passwordResetRateLimiter.tryAcquire("nobody@example.com")).thenReturn(true);
        when(memberRepository.existsByEmail("nobody@example.com")).thenReturn(false);

        memberService.sendPasswordResetEmail(request);

        // 미가입이어도 예외 없이 끝나고, 토큰 저장·메일 발송은 하지 않는다.
        verify(passwordResetTokenStore, never()).save(anyString(), anyString());
        verifyNoInteractions(emailSender, idpUserClient);
    }

    @Test
    @DisplayName("MEM-04: 재설정 메일 rate-limit 초과 → COMMON4291, 가입조회/토큰/메일 모두 미진입(메일폭탄 차단)")
    void sendPasswordResetEmail_rateLimit_초과_COMMON4291() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "victim@example.com");
        when(passwordResetRateLimiter.tryAcquire("victim@example.com")).thenReturn(false);

        assertThatThrownBy(() -> memberService.sendPasswordResetEmail(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);

        // rate-limit이 가입 여부 확인 *전*에 차단 — 가입조회/토큰/메일 모두 미진입.
        verify(memberRepository, never()).existsByEmail(anyString());
        verify(passwordResetTokenStore, never()).save(anyString(), anyString());
        verifyNoInteractions(emailSender, idpUserClient);
    }

    @Test
    @DisplayName("재설정 실행: 유효한 토큰이면 원자 소비(consume) 후 IdP 비번 변경")
    void resetPassword_성공() {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "token", "valid-token");
        ReflectionTestUtils.setField(request, "newPassword", "NewP@ssw0rd!");
        when(passwordResetTokenStore.consume("valid-token")).thenReturn(Optional.of("user@example.com"));

        memberService.resetPassword(request);

        verify(passwordResetTokenStore).consume("valid-token"); // 원자 소비(GETDEL) — 별도 delete 없음
        verify(idpUserClient).changePassword("user@example.com", "NewP@ssw0rd!");
    }

    @Test
    @DisplayName("재설정 실행: 토큰이 만료/무효(Redis에 없음)면 MEMBER4004, 비번 변경 안 함")
    void resetPassword_토큰무효() {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "token", "expired-token");
        ReflectionTestUtils.setField(request, "newPassword", "NewP@ssw0rd!");
        when(passwordResetTokenStore.consume("expired-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.resetPassword(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);

        verify(idpUserClient, never()).changePassword(anyString(), anyString());
    }

    // ───────────────────────── 언어 조회/변경 ─────────────────────────

    @Test
    @DisplayName("getLanguage: 활성 회원의 주 사용 언어를 반환한다")
    void getLanguage_성공() {
        Member member = memberWithLanguage("vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        LanguageResponse response = memberService.getLanguage("pub-1");

        assertThat(response.getLanguage()).isEqualTo("vi");
        // 조회만 — IdP를 건드리지 않아야 한다.
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("getLanguage: 없는(탈퇴 포함) 회원이면 MEMBER4001")
    void getLanguage_없는회원_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getLanguage("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("updateLanguage: 변경 후 새 언어를 반환하고 엔티티 값도 바뀐다(dirty checking)")
    void updateLanguage_성공() {
        Member member = memberWithLanguage("vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        LanguageResponse response = memberService.updateLanguage("pub-1", "ko");

        assertThat(response.getLanguage()).isEqualTo("ko");
        assertThat(member.getLanguage()).as("dirty checking 대상 엔티티도 변경됨").isEqualTo("ko");
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("updateLanguage: 없는(탈퇴 포함) 회원이면 MEMBER4001")
    void updateLanguage_없는회원_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.updateLanguage("nope", "ko"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(idpUserClient);
    }

    // ───────────────────────────── 탈퇴 ─────────────────────────────

    @Test
    @DisplayName("withdraw: 로컬 soft delete 후 IdP 비활성화(deactivateUser)를 호출한다")
    void withdraw_성공() {
        Member member = memberWithLanguage("vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        memberService.withdraw("pub-1");

        assertThat(member.getDeletedAt()).as("로컬 soft delete(deleted_at) 세팅됨").isNotNull();
        // IdP에는 가입 시 저장한 authProviderId(=user uuid)로 비활성화 요청이 나가야 한다.
        verify(idpUserClient).deactivateUser("idp-sub-uuid-1");
    }

    @Test
    @DisplayName("withdraw: 없는(탈퇴 포함) 회원이면 MEMBER4001 + IdP를 건드리지 않는다")
    void withdraw_없는회원_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.withdraw("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        // 없는 회원에게는 IdP 비활성화를 시도하지 않는다(로컬 조회가 먼저).
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("withdraw: IdP 비활성화 실패(COMMON5000) 시 예외를 전파한다(@Transactional 롤백 영역)")
    void withdraw_IdP실패_예외전파() {
        Member member = memberWithLanguage("vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));
        doThrow(new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR))
                .when(idpUserClient).deactivateUser("idp-sub-uuid-1");

        assertThatThrownBy(() -> memberService.withdraw("pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);

        verify(idpUserClient).deactivateUser("idp-sub-uuid-1");
    }

    /** publicId="pub-1", authProviderId="idp-sub-uuid-1" 고정. 언어만 바꿔가며 쓴다. */
    private Member memberWithLanguage(String language) {
        return memberWith("gildong", language);
    }

    /** publicId="pub-1" 고정. 닉네임/언어를 바꿔가며 쓴다(프로필 테스트용). */
    private Member memberWith(String nickname, String language) {
        return Member.builder()
                .publicId("pub-1")
                .email("a@example.com")
                .name("홍길동")
                .nickname(nickname)
                .nationality("VN")
                .language(language)
                .authProviderId("idp-sub-uuid-1")
                .build();
    }

    private ProfileUpdateRequest profileUpdateRequest(String nickname, String language, String bio) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ReflectionTestUtils.setField(request, "language", language);
        ReflectionTestUtils.setField(request, "bio", bio);
        return request;
    }

    // ───────────────────────── 내 프로필 조회/수정 ─────────────────────────

    @Test
    @DisplayName("getMyProfile: 활성 회원 프로필 반환. 미구현 도메인 필드는 기본값(미인증/GREEN/null)")
    void getMyProfile_성공() {
        Member member = memberWith("global_neighbor", "ko");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        ProfileResponse response = memberService.getMyProfile("pub-1");

        assertThat(response.getNickname()).isEqualTo("global_neighbor");
        assertThat(response.getNationality()).isEqualTo("VN");
        assertThat(response.getLanguage()).isEqualTo("ko");
        // 아직 안 만든 도메인 필드는 기본값으로 내려간다.
        assertThat(response.getIsVerified()).isFalse();
        assertThat(response.getTemperatureGrade()).isEqualTo("GREEN");
        assertThat(response.getProfileImageUrl()).isNull();
        verifyNoInteractions(idpUserClient);
    }

    @Test
    @DisplayName("getMyProfile: createdAt은 ISO-8601 UTC 'Z' 문자열(초 단위 절삭)로 직렬화된다")
    void getMyProfile_createdAt_UTC_Z_포맷() {
        Member member = memberWith("global_neighbor", "ko");
        // 단위 테스트라 @PrePersist 미동작 → reflection 세팅이 auditing에 덮이지 않는다. 0.5초 → 초 단위 절삭 확인.
        ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.of(2026, 6, 3, 18, 21, 8, 500_000_000));
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        ProfileResponse response = memberService.getMyProfile("pub-1");

        assertThat(response.getCreatedAt()).isEqualTo("2026-06-03T18:21:08Z");
    }

    @Test
    @DisplayName("getMyProfile: 없는(탈퇴 포함) 회원이면 MEMBER4001")
    void getMyProfile_없는회원_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getMyProfile("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("updateMyProfile: 닉네임을 다른 값으로 바꾸면 중복확인 후 엔티티가 갱신된다(dirty checking)")
    void updateMyProfile_성공_닉네임변경() {
        Member member = memberWith("old_nick", "vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("new_nick")).thenReturn(false);

        ProfileResponse response = memberService.updateMyProfile(
                "pub-1", profileUpdateRequest("new_nick", "ko", "안녕하세요."));

        assertThat(response.getNickname()).isEqualTo("new_nick");
        assertThat(member.getNickname()).isEqualTo("new_nick");
        assertThat(member.getLanguage()).isEqualTo("ko");
        assertThat(member.getBio()).isEqualTo("안녕하세요.");
    }

    @Test
    @DisplayName("updateMyProfile: 닉네임이 다른 회원과 중복이면 MEMBER4003")
    void updateMyProfile_닉네임중복_MEMBER4003() {
        Member member = memberWith("old_nick", "vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("taken")).thenReturn(true);

        assertThatThrownBy(() -> memberService.updateMyProfile(
                "pub-1", profileUpdateRequest("taken", "ko", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.NICKNAME_ALREADY_EXISTS);

        // 거절됐으니 엔티티는 그대로.
        assertThat(member.getNickname()).isEqualTo("old_nick");
    }

    @Test
    @DisplayName("updateMyProfile: 닉네임을 그대로 두면 중복확인을 하지 않고 언어/자기소개만 갱신한다")
    void updateMyProfile_닉네임동일_중복확인안함() {
        Member member = memberWith("same_nick", "vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        memberService.updateMyProfile("pub-1", profileUpdateRequest("same_nick", "ko", "수정함"));

        assertThat(member.getLanguage()).isEqualTo("ko");
        assertThat(member.getBio()).isEqualTo("수정함");
        // 닉네임이 같으면 중복확인 쿼리를 호출하지 않는다.
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    @Test
    @DisplayName("updateMyProfile: 없는(탈퇴 포함) 회원이면 MEMBER4001")
    void updateMyProfile_없는회원_MEMBER4001() {
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.updateMyProfile(
                "nope", profileUpdateRequest("any", "ko", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }
}
