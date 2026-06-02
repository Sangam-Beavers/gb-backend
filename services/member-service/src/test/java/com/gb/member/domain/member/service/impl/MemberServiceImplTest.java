package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.domain.member.dto.request.PasswordResetEmailRequest;
import com.gb.member.domain.member.dto.request.PasswordResetRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.LanguageResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.mail.EmailSender;
import com.gb.member.global.redis.PasswordResetTokenStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        // IdP가 사용자를 만들고 식별자(sub=uuid)를 돌려준다. publicId는 Service가 만들어 4번째 인자로 넘긴다.
        when(idpUserClient.provisionUser(eq("new@example.com"), eq("홍길동"), eq("P@ssw0rd!"), anyString()))
                .thenReturn("idp-sub-uuid-9999");
        // 저장 시 publicId는 Service가 이미 채워 넘기므로 그대로 돌려준다(덮어쓰지 않는다).
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SignupResponse response = memberService.signup(request);

        // then
        assertThat(response.getEmail()).isEqualTo("new@example.com");
        assertThat(response.getNickname()).isEqualTo("gildong");

        // publicId가 IdP(attributes)와 우리 회원에 "같은 값"으로 들어가야 한다(토큰 sub ↔ publicId 매핑의 핵심).
        ArgumentCaptor<String> idpPublicId = ArgumentCaptor.forClass(String.class);
        verify(idpUserClient).provisionUser(eq("new@example.com"), eq("홍길동"), eq("P@ssw0rd!"), idpPublicId.capture());

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getPublicId()).isEqualTo(idpPublicId.getValue());
        // 응답 publicId도 동일해야 한다.
        assertThat(response.getPublicId()).isEqualTo(idpPublicId.getValue());
        // IdP가 준 sub가 회원의 authProviderId로 저장돼야 한다.
        assertThat(saved.getValue().getAuthProviderId()).isEqualTo("idp-sub-uuid-9999");
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

        verify(memberRepository, never()).save(any());
        // 중복이면 IdP에도 사용자를 만들지 않는다(로컬 검증이 먼저).
        verify(idpUserClient, never()).provisionUser(any(), any(), any(), any());
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
    @DisplayName("재설정 메일: 가입된 이메일이면 토큰 저장 + 메일 발송한다")
    void sendPasswordResetEmail_가입됨_발송() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "user@example.com");
        when(memberRepository.existsByEmail("user@example.com")).thenReturn(true);

        memberService.sendPasswordResetEmail(request);

        verify(passwordResetTokenStore).save(anyString(), eq("user@example.com"));
        verify(emailSender).send(eq("user@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("재설정 메일: 미가입 이메일이면 조용히 종료(토큰/메일 없음 — 가입여부 노출 방지)")
    void sendPasswordResetEmail_미가입_조용히종료() {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", "nobody@example.com");
        when(memberRepository.existsByEmail("nobody@example.com")).thenReturn(false);

        memberService.sendPasswordResetEmail(request);

        // 미가입이어도 예외 없이 끝나고, 토큰 저장·메일 발송은 하지 않는다.
        verify(passwordResetTokenStore, never()).save(anyString(), anyString());
        verifyNoInteractions(emailSender, idpUserClient);
    }

    @Test
    @DisplayName("재설정 실행: 유효한 토큰이면 IdP 비번 변경 후 토큰 삭제")
    void resetPassword_성공() {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "token", "valid-token");
        ReflectionTestUtils.setField(request, "newPassword", "NewP@ssw0rd!");
        when(passwordResetTokenStore.findEmail("valid-token")).thenReturn(Optional.of("user@example.com"));

        memberService.resetPassword(request);

        verify(idpUserClient).changePassword("user@example.com", "NewP@ssw0rd!");
        verify(passwordResetTokenStore).delete("valid-token");
    }

    @Test
    @DisplayName("재설정 실행: 토큰이 만료/무효(Redis에 없음)면 MEMBER4004, 비번 변경 안 함")
    void resetPassword_토큰무효() {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "token", "expired-token");
        ReflectionTestUtils.setField(request, "newPassword", "NewP@ssw0rd!");
        when(passwordResetTokenStore.findEmail("expired-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.resetPassword(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);

        verify(idpUserClient, never()).changePassword(anyString(), anyString());
        verify(passwordResetTokenStore, never()).delete(anyString());
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
        return Member.builder()
                .publicId("pub-1")
                .email("a@example.com")
                .name("홍길동")
                .nickname("gildong")
                .nationality("VN")
                .language(language)
                .authProviderId("idp-sub-uuid-1")
                .build();
    }
}
