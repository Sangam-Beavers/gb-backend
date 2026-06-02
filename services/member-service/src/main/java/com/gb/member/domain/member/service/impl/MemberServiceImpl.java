package com.gb.member.domain.member.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.request.PasswordResetEmailRequest;
import com.gb.member.domain.member.dto.request.PasswordResetRequest;
import com.gb.member.domain.member.dto.request.SignupRequest;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.mail.EmailSender;
import com.gb.member.global.redis.PasswordResetTokenStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final IdpUserClient idpUserClient;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final EmailSender emailSender;

    /** 재설정 링크 베이스 URL(프론트 비번재설정 페이지). yml app.password-reset.base-url로 주입. */
    @Value("${app.password-reset.base-url}")
    private String passwordResetBaseUrl;

    @Override
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }

        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        // publicId를 먼저 생성한다. IdP(attributes.public_id)와 우리 DB에 같은 값을 써야
        // 토큰 custom claim(public_id)과 우리 회원이 일치한다(토큰 sub ↔ publicId 매핑).
        String publicId = UUID.randomUUID().toString();

        // 방식 B: 비밀번호는 우리 DB에 저장하지 않는다. IdP가 보유·검증한다.
        // 먼저 IdP에 사용자를 등록(비번 + publicId attribute 포함)하고, IdP가 부여한 식별자(sub)를 받아 authProviderId에 채운다.
        // IdP 등록이 실패하면 여기서 예외가 나 트랜잭션이 롤백되므로 로컬 회원도 생성되지 않는다(정합성).
        String authProviderId = idpUserClient.provisionUser(
                request.getEmail(), request.getName(), request.getPassword(), publicId);

        Member member = Member.builder()
                .publicId(publicId)
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

    @Override
    @Transactional(readOnly = true)
    public void sendPasswordResetEmail(PasswordResetEmailRequest request) {
        String email = request.getEmail();

        // 가입 여부 노출 방지(보안): 미가입 이메일이어도 예외/다른 응답 없이 조용히 종료한다.
        // (공격자가 응답 차이로 "이 이메일 가입돼 있나"를 알아내지 못하게 — 호출 측은 항상 200을 받는다.)
        if (!memberRepository.existsByEmail(email)) {
            return;
        }

        // 일회용 재설정 토큰 생성 → Redis에 TTL 저장(토큰→email). 만료는 Redis가 자동 처리.
        String token = UUID.randomUUID().toString();
        passwordResetTokenStore.save(token, email);

        // 재설정 링크를 메일로 발송. 링크는 프론트 비번재설정 페이지로 향한다(토큰을 쿼리로 전달).
        String link = passwordResetBaseUrl + "?token=" + token;
        emailSender.send(
                email,
                "[Global Bridge] 비밀번호 재설정 안내",
                "아래 링크에서 비밀번호를 재설정해주세요(30분 내 유효):\n\n" + link
                        + "\n\n본인이 요청하지 않았다면 이 메일을 무시하세요.");
    }

    @Override
    @Transactional(readOnly = true)
    public void resetPassword(PasswordResetRequest request) {
        // 토큰 검증 — Redis에 없으면(만료/무효) 거절.
        String email = passwordResetTokenStore.findEmail(request.getToken())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_RESET_TOKEN));

        // 비밀번호는 IdP가 보유하므로 IdP 관리 API로 변경한다.
        idpUserClient.changePassword(email, request.getNewPassword());

        // 사용 완료된 토큰 삭제(재사용 방지).
        passwordResetTokenStore.delete(request.getToken());
    }
}
