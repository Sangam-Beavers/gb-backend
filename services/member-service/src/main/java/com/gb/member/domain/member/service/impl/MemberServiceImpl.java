package com.gb.member.domain.member.service.impl;

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
    @Transactional
    public SocialProfileResponse completeSocialProfile(
            String publicId, String email, String name, String authProviderId,
            SocialProfileRequest request) {

        // 1) 이미 프로필 완료(=members row 존재)면 재생성 거절.
        //    소셜 신규회원은 토큰(public_id)은 있어도 row가 없는 "미완료" 상태로 시작하므로,
        //    row가 이미 있으면 완료된 회원이다(중복 호출/이중 제출).
        if (memberRepository.existsByPublicId(publicId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        // 2) 이메일 중복(다른 계정이 이미 사용). 정책상 소셜-기존 계정 자동연결은 안 하므로(거부),
        //    Authentik Source 단계에서 일차 차단되지만 정합성을 위해 여기서도 방어한다.
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 3) 닉네임 중복(회원가입과 동일 정책).
        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        // 4) members row 최초 생성. publicId/email/name/authProviderId는 검증된 토큰 claim에서,
        //    닉네임/국적/언어는 요청에서 채운다. 모든 필드가 갖춰진 시점에 한 번에 INSERT(이메일 가입과 일관).
        Member member = Member.builder()
                .publicId(publicId)
                .email(email)
                .name(name)
                .nickname(request.getNickname())
                .nationality(request.getNationality())
                .language(request.getLanguage())
                .authProviderId(authProviderId)
                .build();

        Member savedMember = memberRepository.save(member);

        return SocialProfileResponse.from(savedMember);
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

    @Override
    @Transactional(readOnly = true)
    public LanguageResponse getLanguage(String userPublicId) {
        return LanguageResponse.from(getActiveMemberOrThrow(userPublicId));
    }

    @Override
    @Transactional
    public LanguageResponse updateLanguage(String userPublicId, String language) {
        Member member = getActiveMemberOrThrow(userPublicId);
        member.changeLanguage(language);          // dirty checking + Auditing(updatedAt) 자동 갱신
        return LanguageResponse.from(member);
    }

    @Override
    @Transactional
    public void withdraw(String userPublicId) {
        Member member = getActiveMemberOrThrow(userPublicId);
        member.softDelete();                      // 로컬 deleted_at 세팅(아직 커밋 전)
        // 외부 호출은 "마지막 단계"로 — IdP 비활성화가 실패하면 BusinessException이 올라와
        // @Transactional이 롤백되어 로컬 soft delete도 반영되지 않는다(정합성).
        // TODO(알려진 한계): IdP 비활성화 성공 직후 DB 커밋이 실패하는 드문 구간은 이중 쓰기(dual-write)라
        //   완전 원자적이지 않다(IdP만 비활성·로컬 활성). 완전 해소는 PENDING_WITHDRAWAL 상태 +
        //   outbox/재시도 워커(saga)가 필요하나 인프라 비용이 커 v1 범위 밖으로 보류한다.
        idpUserClient.deactivateUser(member.getAuthProviderId());
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(String userPublicId) {
        return ProfileResponse.from(getActiveMemberOrThrow(userPublicId));
    }

    @Override
    @Transactional
    public ProfileResponse updateMyProfile(String userPublicId, ProfileUpdateRequest request) {
        Member member = getActiveMemberOrThrow(userPublicId);

        // 닉네임을 "다른 값"으로 바꿀 때만 중복 확인(자기 자신의 현재 닉네임은 제외).
        if (!member.getNickname().equals(request.getNickname())
                && memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        member.updateProfile(request.getNickname(), request.getLanguage(), request.getBio()); // dirty checking
        return ProfileResponse.from(member);
    }

    /** 탈퇴하지 않은(활성) 회원을 publicId로 조회한다. 없으면 MEMBER4001. */
    private Member getActiveMemberOrThrow(String userPublicId) {
        return memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
