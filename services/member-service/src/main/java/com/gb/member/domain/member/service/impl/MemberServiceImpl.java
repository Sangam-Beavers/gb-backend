package com.gb.member.domain.member.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final IdpUserClient idpUserClient;

    @Override
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }

        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        // 방식 B: 비밀번호는 우리 DB에 저장하지 않는다. IdP가 보유·검증한다.
        // 먼저 IdP에 사용자를 등록(비번 포함)하고, IdP가 부여한 식별자(sub)를 받아 authProviderId에 채운다.
        // IdP 등록이 실패하면 여기서 예외가 나 트랜잭션이 롤백되므로 로컬 회원도 생성되지 않는다(정합성).
        String authProviderId = idpUserClient.provisionUser(
                request.getEmail(), request.getName(), request.getPassword());

        Member member = Member.builder()
                .email(request.getEmail())
                .name(request.getName())
                .nickname(request.getNickname())
                .nationality(request.getNationality())
                .language(request.getLanguage())
                .authProviderId(authProviderId)
                .build();

        Member savedMember = memberRepository.save(member);

        return SignupResponse.from(savedMember);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckAvailabilityResponse checkEmail(String email) {
        // 존재하면 사용 불가(available=false), 없으면 사용 가능(true)
        boolean available = !memberRepository.existsByEmail(email);
        return CheckAvailabilityResponse.of(available);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckAvailabilityResponse checkNickname(String nickname) {
        boolean available = !memberRepository.existsByNickname(nickname);
        return CheckAvailabilityResponse.of(available);
    }
}
