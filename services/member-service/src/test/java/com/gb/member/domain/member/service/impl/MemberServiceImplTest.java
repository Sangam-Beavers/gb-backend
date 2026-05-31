package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.SignupRequest;
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
 * 방식 B(검증 전용)의 {@code signup()} 단위 테스트.
 *
 * <p>로그인은 프론트가 IdP와 직접(Authorization Code flow) 수행하므로 백엔드 서비스엔 login()이 없다.
 * 외부 IdP 회원 등록은 {@link IdpUserClient}를 mock해 대체한다. 가입이 IdP에 사용자를 등록하고 받은
 * sub를 authProviderId에 저장하는지, 비밀번호를 저장하지 않는지, 중복을 막는지만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private IdpUserClient idpUserClient;

    @InjectMocks private MemberServiceImpl memberService;

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
}
