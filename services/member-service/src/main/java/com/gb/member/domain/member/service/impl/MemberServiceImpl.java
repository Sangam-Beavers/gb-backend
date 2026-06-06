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
import com.gb.member.domain.member.dto.response.MemberDisplayListResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayResponse;
import com.gb.member.domain.member.dto.response.ProfileResponse;
import com.gb.member.domain.member.dto.response.SignupResponse;
import com.gb.member.domain.member.dto.response.SocialProfileResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.MemberService;
import com.gb.member.global.client.IdpUserClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import com.gb.member.global.mail.EmailSender;
import com.gb.member.global.redis.PasswordResetRateLimiter;
import com.gb.member.global.redis.PasswordResetTokenStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final IdpUserClient idpUserClient;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final PasswordResetRateLimiter passwordResetRateLimiter;
    private final EmailSender emailSender;

    /** self-injection: withdrawLocalTx의 @Transactional 프록시 적용 위함(VerificationServiceImpl 동일 패턴). */
    @Autowired
    @Lazy
    private MemberService self;

    /** 재설정 링크 베이스 URL(프론트 비번재설정 페이지). yml app.password-reset.base-url로 주입. */
    @Value("${app.password-reset.base-url}")
    private String passwordResetBaseUrl;

    /**
     * 이메일 회원가입 — <b>IdP-first 단일 INSERT</b>.
     *
     * <p>순서: 중복 선검사 → IdP 프로비저닝(<b>트랜잭션 밖</b>) → 로컬 단일 INSERT(sub 포함, 자체 짧은 tx).
     * 과거(MEM-02)에는 "로컬 row 선점 → IdP → sub UPDATE"를 한 @Transactional로 묶었으나,
     * ① IdP HTTP(호출당 최대 ~13s × 2회)가 tx 안에 있어 UNIQUE/행 락과 Hikari 커넥션을 점유했고(core-2)
     * ② set_password 실패 시 IdP 고아가 남아 그 이메일이 영구 가입불가였다(idp-1).
     * 보상 삭제가 생기면서 "로컬 선점으로 고아를 막는다"는 전제가 바뀌어 IdP-first로 재설계했다:
     * <ul>
     *   <li>이메일 race는 IdP username unique가 직렬화 — 패자는 MEMBER4002(409, unique 매핑)를 받고
     *       IdP에 아무것도 만들지 못하므로 고아가 없다.</li>
     *   <li>프로비저닝 부분실패(② set_password)는 클라이언트 내부 보상 DELETE로 회수된다.</li>
     *   <li>로컬 INSERT 실패(닉네임 race·DB 장애)는 방금 만든 IdP 사용자를 best-effort 회수한다.</li>
     *   <li>커밋되는 row는 항상 sub까지 포함한 완전한 형상 — "sub 없는 회원" 상태가 존재하지 않는다
     *       (auth_provider_id NOT NULL, database.md §members 일치).</li>
     * </ul>
     */
    @Override
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

        // ① IdP 프로비저닝 — 트랜잭션 "밖"에서 수행하여 DB 커넥션/락을 점유하지 않는다.
        //    set_password 부분실패 시 내부 보상 DELETE로 IdP 고아를 회수한다.
        //    비밀번호는 IdP에만 저장되며, 로컬 DB에는 저장하지 않는다.
        String authProviderId = idpUserClient.provisionUser(
                request.getEmail(), request.getName(), request.getPassword(), publicId);

        // ② 로컬 단일 INSERT(자체 짧은 tx) — sub까지 포함한 완전한 형상으로만 커밋한다.
        Member member = Member.builder()
                .publicId(publicId)
                .email(request.getEmail())
                .name(request.getName())
                .nickname(request.getNickname())
                .nationality(request.getNationality())
                .language(request.getLanguage())
                .authProviderId(authProviderId)
                // TODO(약관): 프론트 미연동 — 미전송(null)은 임시로 동의(true)로 처리한다(@AssertTrue가 명시 false는 차단).
                //   프론트가 동의 값을 전송하면 SignupRequest @NotNull 복구 + 아래 null 기본처리를 제거한다.
                .termsAgreed(request.getTermsAgreed() == null || request.getTermsAgreed())
                .privacyAgreed(request.getPrivacyAgreed() == null || request.getPrivacyAgreed())
                .consentAgreedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        Member savedMember;
        try {
            savedMember = memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException race) {
            // 선검사를 통과한 동시 가입 race가 UNIQUE에 걸린 경우(이메일은 IdP unique가 먼저 직렬화하므로
            // 주로 닉네임/publicId). 어느 제약인지 구분은 비이식적이라 generic "이미 존재"(COMMON4091)로
            // 통일한다(member-5 정책 유지). 방금 만든 IdP 사용자는 회수해 이메일을 풀어 준다(고아 방지).
            idpUserClient.deleteUserBestEffort(authProviderId);
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        } catch (RuntimeException dbFailure) {
            // DB 장애 등 — IdP에만 사용자가 남은 채 끝나지 않도록 회수 후 원예외 전파(중앙 핸들러 500).
            idpUserClient.deleteUserBestEffort(authProviderId);
            throw dbFailure;
        }

        return SignupResponse.from(savedMember);
    }

    @Override
    @Transactional
    public SocialProfileResponse completeSocialProfile(
            String publicId, String email, String name, String authProviderId,
            SocialProfileRequest request) {

        // 1) 이미 프로필 완료(=members row 존재)면 재생성 거절.
        if (memberRepository.existsByPublicId(publicId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        // 2) 이메일 중복(다른 계정이 이미 사용). 여기서도 방어하여 정합성을 확보한다.
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 3) 닉네임 중복(회원가입과 동일 정책).
        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        Member member = Member.builder()
                .publicId(publicId)
                .email(email)
                .name(name)
                .nickname(request.getNickname())
                .nationality(request.getNationality())
                .language(request.getLanguage())
                .authProviderId(authProviderId)
                // TODO(약관): 이메일 가입과 동일 임시 정책 — 미전송(null)은 동의(true)로 처리한다(consent_agreed_at은 NOT NULL).
                //   프론트 연동 후 SocialProfileRequest @NotNull 복구 + null 기본처리 제거.
                .termsAgreed(request.getTermsAgreed() == null || request.getTermsAgreed())
                .privacyAgreed(request.getPrivacyAgreed() == null || request.getPrivacyAgreed())
                .consentAgreedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        // saveAndFlush로 INSERT를 이 메서드 안에서 강제해, 위 existsBy를 통과한 동시 호출 race의 UNIQUE 위반
        // (publicId/email/nickname)을 contextual하게 잡는다(중앙 핸들러는 이제 DataIntegrityViolation을 500으로
        // 처리하므로 여기서 COMMON4091로 변환해야 race가 409로 유지된다 — 위 existsByPublicId 분기와 동일 코드).
        // member-5: race는 어느 필드(email/nickname)인지 구분 없이 generic COMMON4091로 통일한다 — 도메인 코드
        // (MEMBER4002/4003)는 순차 선검사(existsBy)에서만 주고, 드문 동시 race는 "이미 존재"로 충분하다(의도된 트레이드오프).
        Member savedMember;
        try {
            savedMember = memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

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
    public MemberDisplayListResponse getDisplayInfos(List<String> publicIds) {
        // 탈퇴자는 Repository 레벨에서 제외되며,
        // 미존재·탈퇴로 빠진 id는 응답에 항목이 없을 뿐 에러가 아니다.
        List<MemberDisplayResponse> members = memberRepository
                .findByPublicIdInAndDeletedAtIsNull(publicIds).stream()
                .map(MemberDisplayResponse::from)
                .toList();
        return MemberDisplayListResponse.of(members);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberDisplayResponse getDisplayInfoByEmail(String email) {
        // 검증(존재 확인) 용도라 폴백 없이 fail-fast(§7) — 미존재·탈퇴 모두 MEMBER4001.
        // 탈퇴자 제외는 Repository 레벨(findByEmailAndDeletedAtIsNull): 탈퇴 회원은 송금 수신자가 될 수 없다.
        Member member = memberRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        return MemberDisplayResponse.from(member);
    }

    @Override
    @Transactional(readOnly = true)
    public void sendPasswordResetEmail(PasswordResetEmailRequest request) {
        String email = request.getEmail();

        // MEM-04 — 이메일 단위 rate-limit으로 메일 폭탄을 막는다. 가입 여부 확인 *전*에 적용해 가입/미가입
        // 사이의 처리 시간 차이도 일부 줄인다(타이밍 enumeration 완화 — 완전 상수시간은 아님). 초과 시
        // COMMON4291(429). Redis 장애 시 fail-open(통과).
        if (!passwordResetRateLimiter.tryAcquire(email)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        // 가입 여부 노출 방지(보안): 미가입 이메일이어도 예외/다른 응답 없이 조용히 종료한다.
        // (공격자가 응답 차이로 "이 이메일 가입돼 있나"를 알아내지 못하게 — 호출 측은 항상 200을 받는다.)
        // 저장 이메일(가입 당시 표기)을 사용한다. MySQL 기본 collation은 대소문자를 무시하므로,
        // IdP username과 byte-exact 일치를 보장하기 위해 입력값이 아닌 저장값을 사용한다.
        Optional<Member> member = memberRepository.findByEmail(email);
        if (member.isEmpty()) {
            return;
        }
        String canonicalEmail = member.get().getEmail();

        String token = UUID.randomUUID().toString();
        passwordResetTokenStore.save(token, canonicalEmail);

        // 재설정 링크를 메일로 발송. 링크는 프론트 비번재설정 페이지로 향한다(토큰을 쿼리로 전달).
        // 유효 시간 문구는 토큰 TTL 단일 출처에서 가져온다(MEM-08 — 리터럴 분리로 인한 불일치 방지).
        String link = passwordResetBaseUrl + "?token=" + token;
        emailSender.send(
                canonicalEmail,
                "[Global Bridge] 비밀번호 재설정 안내",
                "아래 링크에서 비밀번호를 재설정해주세요(" + passwordResetTokenStore.ttlMinutes()
                        + "분 내 유효):\n\n" + link
                        + "\n\n본인이 요청하지 않았다면 이 메일을 무시하세요.");
    }

    @Override
    public void resetPassword(PasswordResetRequest request) {
        // 토큰을 원자적으로 소비(GETDEL)하여 단 한 번만 사용되게 한다.
        // 없으면(만료/무효/이미 소비) 거절한다.
        String email = passwordResetTokenStore.consume(request.getToken())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_RESET_TOKEN));

        // 비밀번호는 IdP가 보유하므로 IdP 관리 API로 변경한다.
        idpUserClient.changePassword(email, request.getNewPassword());
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

    /**
     * 탈퇴 — <b>IdP-first + 로컬 짧은 tx</b>(가입 IdP-first·인증 지갑개설 hoist와 동일 사상).
     *
     * <p>과거에는 한 @Transactional 안에서 soft delete 후 IdP HTTP(호출당 최대 ~13s)를 기다려
     * "IdP 실패 → 롤백" 정합을 얻었으나, 그 대가로 쓰기 tx·Hikari 커넥션을 외부 응답까지 점유했다(core-2 계열).
     * IdP 비활성화를 tx 밖으로 빼고 로컬 soft delete를 자체 짧은 tx로 분리해도 같은 정합이 유지된다:
     * <ul>
     *   <li>IdP 비활성화 실패 → 여기서 예외로 끝나 로컬 무변경(기존 롤백과 동일한 결과).</li>
     *   <li>로컬 soft delete 실패 → "IdP만 비활성·로컬 활성" — 기존 코드의 커밋 실패 구간과 동일한
     *       잔여 상태이며, {@link IdpUserClient#deactivateUser}가 멱등이라 재시도(액세스 토큰 만료 전)로 수습된다.
     *       완전 해소는 PENDING_WITHDRAWAL + outbox/재시도 워커(saga)가 필요하나 v1 범위 밖(기존 보류 유지).</li>
     * </ul>
     */
    @Override
    public void withdraw(String userPublicId) {
        // 활성 회원 확인 + IdP 식별자 확보(조회만 — 쓰기 tx를 열지 않는다). 없는(탈퇴 포함) 회원이면 MEMBER4001.
        Member member = getActiveMemberOrThrow(userPublicId);

        // ① IdP 비활성화 — 트랜잭션 "밖". 실패하면 BusinessException이 그대로 올라와 로컬은 무변경.
        idpUserClient.deactivateUser(member.getAuthProviderId());

        // ② 로컬 soft delete — self-proxy 자체 짧은 tx(외부 HTTP가 끝난 뒤에만 커넥션을 잡는다).
        self.withdrawLocalTx(userPublicId);
    }

    @Override
    @Transactional
    public void withdrawLocalTx(String userPublicId) {
        // ①과의 사이에 동시 탈퇴가 끼어든 race까지 재확인 — 이미 탈퇴면 MEMBER4001(순차 재탈퇴와 동일 응답).
        Member member = getActiveMemberOrThrow(userPublicId);
        member.softDelete(); // 로컬 deleted_at 세팅(dirty checking — 커밋은 메서드 종료 시)
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

        member.updateProfile(request.getNickname(), request.getLanguage(), request.getBio());
        return ProfileResponse.from(member);
    }

    /** 탈퇴하지 않은(활성) 회원을 publicId로 조회한다. 없으면 MEMBER4001. */
    private Member getActiveMemberOrThrow(String userPublicId) {
        return memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
