package com.gb.member.global.security;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 표준 인증 흐름에서 회원 조회를 담당한다.
 *
 * <p>{@code AuthenticationManager} → {@code DaoAuthenticationProvider}가 이 빈을 호출해
 * 이메일로 회원을 가져오고, 가져온 해시 비밀번호와 입력 비밀번호를 {@code PasswordEncoder}로 비교한다.
 *
 * <p>회원이 없으면 {@link UsernameNotFoundException}을 던지지만, 호출 측({@code MemberServiceImpl.login})에서
 * {@code BadCredentialsException}(비밀번호 불일치)과 함께 모두
 * {@link com.gb.member.global.exception.code.AuthErrorCode#INVALID_CREDENTIALS}(AUTH4001)로
 * 통일 변환되어 enumeration을 방지한다.
 *
 * <p>권한 모델은 아직 없으므로 authorities는 빈 리스트를 부여한다. 권한 도입 시 이곳을 수정한다.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // 예외 메시지·로그 어디에도 PII(이메일)를 싣지 않는다. 메시지가 로그·스택트레이스·외부 시스템으로
        // 전파될 수 있어서다. 어차피 호출 측에서 AUTH4001로 통일 변환되므로 추가 식별자가 필요 없다.
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Member not found"));

        return new User(member.getEmail(), member.getPassword(), Collections.emptyList());
    }
}
