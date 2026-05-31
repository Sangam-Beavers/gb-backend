package com.gb.member.domain.member.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 이메일/닉네임 중복 확인 단위 테스트.
 *
 * <p>중복 확인은 비밀번호와 무관하므로(조회만), PasswordEncoder는 사용되지 않아야 한다.
 * existsByEmail/existsByNickname 결과의 반전(존재→사용불가, 없음→사용가능)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private MemberServiceImpl memberService;

    @Test
    @DisplayName("이메일이 없으면 사용 가능(available=true)을 반환한다")
    void checkEmail_사용가능() {
        when(memberRepository.existsByEmail("free@example.com")).thenReturn(false);

        CheckAvailabilityResponse response = memberService.checkEmail("free@example.com");

        assertThat(response.isAvailable()).isTrue();
        // 중복 확인은 비밀번호와 무관 — 인코더를 건드리지 않아야 한다.
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("이메일이 이미 있으면 사용 불가(available=false)를 반환한다")
    void checkEmail_중복() {
        when(memberRepository.existsByEmail("dup@example.com")).thenReturn(true);

        CheckAvailabilityResponse response = memberService.checkEmail("dup@example.com");

        assertThat(response.isAvailable()).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("닉네임이 없으면 사용 가능(available=true)을 반환한다")
    void checkNickname_사용가능() {
        when(memberRepository.existsByNickname("freeNick")).thenReturn(false);

        CheckAvailabilityResponse response = memberService.checkNickname("freeNick");

        assertThat(response.isAvailable()).isTrue();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("닉네임이 이미 있으면 사용 불가(available=false)를 반환한다")
    void checkNickname_중복() {
        when(memberRepository.existsByNickname("dupNick")).thenReturn(true);

        CheckAvailabilityResponse response = memberService.checkNickname("dupNick");

        assertThat(response.isAvailable()).isFalse();
        verifyNoInteractions(passwordEncoder);
    }
}
