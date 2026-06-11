package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.gb.member.domain.member.dto.response.MemberDisplayListResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayResponse;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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

    @BeforeEach
    void injectSelfProxy() {
        // 생성자 주입(@RequiredArgsConstructor)에선 @InjectMocks가 비-final self 필드를 채우지 않아 null.
        // 단위 테스트는 프록시 없이 자기 자신을 넣어 withdrawLocalTx 위임을 그대로 실행한다(VerificationServiceImplTest 동일).
        ReflectionTestUtils.setField(memberService, "self", memberService);
    }

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
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        // 약관 동의 필드는 일부러 미설정(null) — 프론트 미전송 상황을 본떠, Service가 동의로 처리하는지 검증한다.

        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        // 로컬 row를 IdP 호출 전에 먼저 선점(saveAndFlush). publicId는 Service가 채워 넘기므로 그대로 돌려준다.
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
        // IdP가 준 sub가 빌더로 채워진 "완전한 형상"으로 INSERT돼야 한다(sub 없는 row 상태 없음 — IdP-first).
        assertThat(saved.getValue().getAuthProviderId()).isEqualTo("idp-sub-uuid-9999");

        // 성별·연령대(이슈 #203)가 enum으로 변환돼 저장돼야 한다.
        assertThat(saved.getValue().getGender()).isEqualTo(com.gb.member.domain.member.entity.Gender.MALE);
        assertThat(saved.getValue().getAgeRange()).isEqualTo(com.gb.member.domain.member.entity.AgeRange.TWENTIES);

        // 약관 동의 증적이 엔티티에 저장돼야 한다(동의값 → 저장 끝까지 검증, consent_agreed_at은 NOT NULL).
        assertThat(saved.getValue().isTermsAgreed()).isTrue();
        assertThat(saved.getValue().isPrivacyAgreed()).isTrue();
        assertThat(saved.getValue().getConsentAgreedAt()).isNotNull();

        // IdP-first 회귀(11D member-idp-1·core-2 — MEM-02 재설계): IdP provision이 로컬 saveAndFlush보다
        // *먼저* 실행돼야 한다. IdP 호출이 트랜잭션/락 밖으로 나가고(core-2), 로컬엔 sub까지 포함한
        // 단일 INSERT만 남는 구조의 핵심 순서다.
        InOrder order = inOrder(memberRepository, idpUserClient);
        order.verify(idpUserClient).provisionUser(any(), any(), any(), any());
        order.verify(memberRepository).saveAndFlush(any(Member.class));
    }

    @Test
    @DisplayName("IdP-first: provision 실패 시 예외 전파 + 로컬 INSERT 미진입(로컬 흔적 0 — 보상 불필요)")
    void signup_provision실패_로컬흔적없음() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "new@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        // 약관 동의 필드는 일부러 미설정(null) — 프론트 미전송 상황을 본떠, Service가 동의로 처리하는지 검증한다.

        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        when(idpUserClient.provisionUser(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("IdP unavailable"));

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(RuntimeException.class);

        // IdP-first(11D core-2): provision이 실패하면 로컬엔 아무것도 만들어진 게 없어야 한다(INSERT 미진입).
        // set_password 부분실패의 IdP 측 보상은 RealIdpUserClient 내부 소관(RealIdpUserClientTest에서 검증).
        verify(memberRepository, never()).saveAndFlush(any(Member.class));
        verify(idpUserClient, never()).deleteUserBestEffort(anyString());
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

    @Test
    @DisplayName("성별 코드가 잘못되면 MEMBER4005로 거절하고 IdP/저장에 도달하지 않는다(이슈 #203)")
    void signup_잘못된성별_MEMBER4005() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "new@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        ReflectionTestUtils.setField(request, "gender", "DOG");        // enum 후보 아님
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.INVALID_GENDER);

        // enum 검증은 IdP 프로비저닝 "전"이라 IdP 고아/로컬 저장이 생기지 않아야 한다(conventions §6, fail-fast).
        verify(idpUserClient, never()).provisionUser(any(), any(), any(), any());
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("연령대 코드가 잘못되면 MEMBER4006으로 거절하고 IdP/저장에 도달하지 않는다(이슈 #203)")
    void signup_잘못된연령대_MEMBER4006() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "new@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "NINETIES");  // enum 후보 아님
        when(memberRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.INVALID_AGE_RANGE);

        verify(idpUserClient, never()).provisionUser(any(), any(), any(), any());
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 가입 race(saveAndFlush UNIQUE 위반) → COMMON4091 + 방금 만든 IdP 사용자 보상 회수")
    void signup_동시가입race_COMMON4091_보상회수() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "race@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        // 약관 동의 필드는 일부러 미설정(null) — 프론트 미전송 상황을 본떠, Service가 동의로 처리하는지 검증한다.
        when(memberRepository.existsByEmail("race@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        when(idpUserClient.provisionUser(any(), any(), any(), any())).thenReturn("idp-sub-race-1");
        // 선검사는 통과했으나 saveAndFlush에서 동시 가입 race가 UNIQUE를 위반(IdP-first라 주로 닉네임 race —
        // 이메일 race는 IdP username unique가 먼저 직렬화해 provisionUser가 MEMBER4002로 끝난다).
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_members_nickname'"));

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS); // 중앙 핸들러(500) 대신 contextual 409

        // 11D member-idp-1: 로컬 INSERT가 실패했으므로 방금 만든 IdP 사용자를 회수해야
        // 그 이메일이 IdP username unique에 막혀 영구 가입불가가 되지 않는다.
        verify(idpUserClient).deleteUserBestEffort("idp-sub-race-1");
    }

    @Test
    @DisplayName("IdP-first: 로컬 INSERT가 일반 장애(DB down)로 실패해도 IdP 사용자 보상 회수 후 원예외 전파")
    void signup_로컬저장_일반장애_보상회수_원예외전파() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "dbdown@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", "홍길동");
        ReflectionTestUtils.setField(request, "nickname", "gildong");
        ReflectionTestUtils.setField(request, "nationality", "VN");
        ReflectionTestUtils.setField(request, "language", "vi");
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        when(memberRepository.existsByEmail("dbdown@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        when(idpUserClient.provisionUser(any(), any(), any(), any())).thenReturn("idp-sub-dbdown-1");
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 원예외가 그대로 전파돼야 한다(중앙 핸들러가 500 처리) — 보상 호출이 예외를 삼키면 안 된다.
        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB connection lost");

        // IdP에만 사용자가 남은 채 끝나지 않도록 회수한다(11D member-idp-1).
        verify(idpUserClient).deleteUserBestEffort("idp-sub-dbdown-1");
    }

    // ──────────────────── 소셜 가입 추가정보 보완 ────────────────────

    private SocialProfileRequest socialProfileRequest(String nickname, String nationality, String language) {
        SocialProfileRequest request = new SocialProfileRequest();
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ReflectionTestUtils.setField(request, "nationality", nationality);
        ReflectionTestUtils.setField(request, "language", language);
        ReflectionTestUtils.setField(request, "gender", "FEMALE");
        ReflectionTestUtils.setField(request, "ageRange", "THIRTIES");
        // 약관 동의는 미설정(null) — 프론트 미전송 상황. Service가 동의로 처리해 저장 값이 true가 돼야 한다.
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
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SocialProfileResponse response = memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request);

        // then — 응답 확인
        assertThat(response.getPublicId()).isEqualTo("pub-uuid-1");
        assertThat(response.getEmail()).isEqualTo("google@example.com");
        assertThat(response.getNickname()).isEqualTo("gildong");

        // 저장된 회원: 토큰 claim은 토큰값, 나머지는 입력값으로 채워져야 한다.
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        Member m = saved.getValue();
        assertThat(m.getPublicId()).isEqualTo("pub-uuid-1");
        assertThat(m.getEmail()).isEqualTo("google@example.com");
        assertThat(m.getName()).isEqualTo("홍길동");
        assertThat(m.getAuthProviderId()).isEqualTo("idp-sub-1");
        assertThat(m.getNickname()).isEqualTo("gildong");
        assertThat(m.getNationality()).isEqualTo("VN");
        assertThat(m.getLanguage()).isEqualTo("vi");
        // 소셜 가입도 성별·연령대를 enum으로 변환해 저장해야 한다(이슈 #203 — 이메일 가입과 동일).
        assertThat(m.getGender()).isEqualTo(com.gb.member.domain.member.entity.Gender.FEMALE);
        assertThat(m.getAgeRange()).isEqualTo(com.gb.member.domain.member.entity.AgeRange.THIRTIES);
        // 소셜 가입도 약관 동의 증적을 저장해야 한다(consent_agreed_at NOT NULL — 미설정 시 INSERT 깨짐 회귀 방지).
        assertThat(m.isTermsAgreed()).isTrue();
        assertThat(m.isPrivacyAgreed()).isTrue();
        assertThat(m.getConsentAgreedAt()).isNotNull();
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

        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("WU-F8: 소셜 보완 existsBy 통과 후 동시 호출 race(saveAndFlush UNIQUE 위반) → COMMON4091(중앙 핸들러 500 대신 contextual 409)")
    void completeSocialProfile_동시race_COMMON4091() {
        SocialProfileRequest request = socialProfileRequest("gildong", "VN", "vi");
        when(memberRepository.existsByPublicId("pub-uuid-1")).thenReturn(false);
        when(memberRepository.existsByEmail("google@example.com")).thenReturn(false);
        when(memberRepository.existsByNickname("gildong")).thenReturn(false);
        // 선검사는 통과했으나 saveAndFlush에서 동시 호출 race가 UNIQUE를 위반.
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_members_public_id'"));

        assertThatThrownBy(() -> memberService.completeSocialProfile(
                "pub-uuid-1", "google@example.com", "홍길동", "idp-sub-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
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

        verify(memberRepository, never()).saveAndFlush(any());
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

        verify(memberRepository, never()).saveAndFlush(any());
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

    // ──────────────────── 표시정보 조회 (display-info / by-email) ────────────────────

    @Test
    @DisplayName("display-info 배치 조회는 Repository IN-batch 1회 결과를 표시정보 DTO로 매핑한다")
    void getDisplayInfos_배치_매핑() {
        Member linh = displayMember("pub-linh", "linh@example.com", "Nguyen Thi Linh", "Linh", "VN");
        Member maria = displayMember("pub-maria", "maria@example.com", "Maria Santos", "Maria", "PH");
        List<String> requested = List.of("pub-linh", "pub-maria", "pub-missing");
        when(memberRepository.findByPublicIdInAndDeletedAtIsNull(requested))
                .thenReturn(List.of(linh, maria));

        MemberDisplayListResponse response = memberService.getDisplayInfos(requested);

        // 존재하는 활성 회원만 항목으로 — 미존재(pub-missing)는 제외(호출 측 Unknown 폴백 계약).
        assertThat(response.getMembers()).hasSize(2);
        MemberDisplayResponse first = response.getMembers().get(0);
        assertThat(first.getPublicId()).isEqualTo("pub-linh");
        assertThat(first.getName()).isEqualTo("Nguyen Thi Linh");
        assertThat(first.getNickname()).isEqualTo("Linh");
        assertThat(first.getNationality()).isEqualTo("VN");
        assertThat(first.getIsVerified()).isFalse();
        // 조회만 — IdP/메일 등 다른 의존을 건드리지 않는다.
        verifyNoInteractions(idpUserClient, emailSender);
    }

    @Test
    @DisplayName("display-info: 요청 id가 전부 미존재·탈퇴면 빈 배열을 반환한다(에러 아님)")
    void getDisplayInfos_전부_미존재_빈배열() {
        List<String> requested = List.of("pub-none-1", "pub-none-2");
        when(memberRepository.findByPublicIdInAndDeletedAtIsNull(requested)).thenReturn(List.of());

        MemberDisplayListResponse response = memberService.getDisplayInfos(requested);

        assertThat(response.getMembers()).isEmpty();
    }

    @Test
    @DisplayName("by-email: 활성 회원이 있으면 표시정보를 반환한다")
    void getDisplayInfoByEmail_성공() {
        Member linh = displayMember("pub-linh", "linh@example.com", "Nguyen Thi Linh", "Linh", "VN");
        when(memberRepository.findByEmailAndDeletedAtIsNull("linh@example.com"))
                .thenReturn(Optional.of(linh));

        MemberDisplayResponse response = memberService.getDisplayInfoByEmail("linh@example.com");

        assertThat(response.getPublicId()).isEqualTo("pub-linh");
        assertThat(response.getNickname()).isEqualTo("Linh");
        verifyNoInteractions(idpUserClient, emailSender);
    }

    @Test
    @DisplayName("by-email: 미존재·탈퇴 회원이면 MEMBER4001로 fail-fast(검증 용도 — 폴백 금지)")
    void getDisplayInfoByEmail_미존재_MEMBER4001() {
        when(memberRepository.findByEmailAndDeletedAtIsNull("ghost@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getDisplayInfoByEmail("ghost@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    /** 표시정보 테스트용 활성 회원(미탈퇴, isVerified 기본 false). */
    private Member displayMember(String publicId, String email, String name, String nickname,
                                 String nationality) {
        return Member.builder()
                .publicId(publicId)
                .email(email)
                .name(name)
                .nickname(nickname)
                .nationality(nationality)
                .language("ko")
                .authProviderId("idp-" + publicId)
                .build();
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
        when(memberRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.of(Member.builder().email("user@example.com").build()));
        when(passwordResetTokenStore.ttlMinutes()).thenReturn(30L);

        memberService.sendPasswordResetEmail(request);

        verify(passwordResetTokenStore).save(anyString(), eq("user@example.com"));
        // MEM-08: 본문 "N분 내 유효"의 N이 TTL 단일출처(ttlMinutes)에서 와야 한다(리터럴 분리 방지).
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("user@example.com"), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("30분 내 유효");
    }

    @Test
    @DisplayName("혼합 케이스 입력이어도 토큰·메일은 회원의 저장 이메일(가입 표기 = IdP username)로 흐른다")
    void sendPasswordResetEmail_혼합케이스_저장이메일사용() {
        // 가입 표기는 "user@example.com"인데 사용자가 "User@Example.COM"으로 요청한 상황.
        // (MySQL 기본 collation은 대소문자 무시라 findByEmail이 회원을 찾는다 — mock으로 본뜸.)
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "User@Example.COM");
        when(passwordResetRateLimiter.tryAcquire("User@Example.COM")).thenReturn(true);
        when(memberRepository.findByEmail("User@Example.COM"))
                .thenReturn(Optional.of(Member.builder().email("user@example.com").build()));
        when(passwordResetTokenStore.ttlMinutes()).thenReturn(30L);

        memberService.sendPasswordResetEmail(request);

        // 입력 표기("User@Example.COM")가 아니라 저장 표기("user@example.com")가 토큰·발송에 쓰여야
        // 재설정 단계의 IdP username 정확 일치(changePassword)가 깨지지 않는다.
        verify(passwordResetTokenStore).save(anyString(), eq("user@example.com"));
        verify(emailSender).send(eq("user@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("재설정 메일: 미가입 이메일이면 조용히 종료(토큰/메일 없음 — 가입여부 노출 방지)")
    void sendPasswordResetEmail_미가입_조용히종료() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "nobody@example.com");
        when(passwordResetRateLimiter.tryAcquire("nobody@example.com")).thenReturn(true);
        when(memberRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

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
        verify(memberRepository, never()).findByEmail(anyString());
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
    @DisplayName("withdraw: IdP 비활성화(tx 밖) 후 로컬 soft delete를 수행한다(IdP-first)")
    void withdraw_성공() {
        Member member = memberWithLanguage("vi");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        memberService.withdraw("pub-1");

        assertThat(member.getDeletedAt()).as("로컬 soft delete(deleted_at) 세팅됨").isNotNull();
        // 활성 조회는 2회 — ① withdraw의 IdP 식별자 확보 ② withdrawLocalTx(짧은 tx)의 race 재확인.
        verify(memberRepository, times(2)).findByPublicIdAndDeletedAtIsNull("pub-1");
        // IdP-first 순서를 직접 구속한다(signup 테스트와 동일 방식): 조회 → IdP 비활성화(tx 밖) → 로컬 단계 재조회.
        InOrder order = inOrder(idpUserClient, memberRepository);
        order.verify(memberRepository).findByPublicIdAndDeletedAtIsNull("pub-1");
        order.verify(idpUserClient).deactivateUser("idp-sub-uuid-1");
        order.verify(memberRepository).findByPublicIdAndDeletedAtIsNull("pub-1");
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
    @DisplayName("withdraw: IdP 비활성화 실패(COMMON5000) 시 예외 전파 + 로컬 soft delete에 도달하지 않는다(IdP-first 정합)")
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
        // 과거엔 @Transactional 롤백이 정합을 보장했지만, hoist 후엔 "로컬에 손대기 전"이라 무변경이 보장된다.
        assertThat(member.getDeletedAt()).as("IdP 실패 시 로컬 무변경").isNull();
    }

    @Test
    @DisplayName("withdraw: IdP 비활성화 뒤 로컬 단계에서 회원이 사라진 race면 MEMBER4001(비활성화는 멱등이라 재시도 안전)")
    void withdraw_로컬단계_race_MEMBER4001() {
        Member member = memberWithLanguage("vi");
        // ① withdraw의 활성 조회는 성공, ② withdrawLocalTx의 재확인 시점엔 동시 탈퇴로 이미 비활성.
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1"))
                .thenReturn(Optional.of(member), Optional.empty());

        assertThatThrownBy(() -> memberService.withdraw("pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        // IdP 비활성화는 이미 나갔지만(잔여 상태), deactivateUser가 멱등이라 중복 호출이어도 무해하다.
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
    @DisplayName("getMyProfile: 활성 회원 프로필 반환. 신규 가입 기본값(미인증/NEWCOMER) + 미구현 이미지 필드는 null")
    void getMyProfile_성공() {
        Member member = memberWith("global_neighbor", "ko");
        when(memberRepository.findByPublicIdAndDeletedAtIsNull("pub-1")).thenReturn(Optional.of(member));

        ProfileResponse response = memberService.getMyProfile("pub-1");

        assertThat(response.getNickname()).isEqualTo("global_neighbor");
        assertThat(response.getNationality()).isEqualTo("VN");
        assertThat(response.getLanguage()).isEqualTo("ko");
        // 신규 가입 기본값: 미인증 + 신뢰등급 NEWCOMER(이슈 #193 — 하드코딩 "GREEN" 제거, 저장값 반환).
        assertThat(response.getIsVerified()).isFalse();
        assertThat(response.getTrustGrade()).isEqualTo("NEWCOMER");
        // 이미지 도메인은 아직 미구현 — 기본값(null)으로 내려간다.
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
