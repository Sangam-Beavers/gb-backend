package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
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
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private IdpUserClient idpUserClient;

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
        // IdP가 사용자를 만들고 식별자(sub=uuid)를 돌려준다.
        when(idpUserClient.provisionUser("new@example.com", "홍길동", "P@ssw0rd!"))
                .thenReturn("idp-sub-uuid-9999");
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
            Member m = invocation.getArgument(0);
            ReflectionTestUtils.setField(m, "publicId", "11111111-1111-1111-1111-111111111111");
            return m;
        });

        // when
        SignupResponse response = memberService.signup(request);

        // then
        assertThat(response.getPublicId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(response.getEmail()).isEqualTo("new@example.com");
        assertThat(response.getNickname()).isEqualTo("gildong");
        // IdP가 준 sub가 회원의 authProviderId로 저장돼야 한다.
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
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
        verify(idpUserClient, never()).provisionUser(any(), any(), any());
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
}
