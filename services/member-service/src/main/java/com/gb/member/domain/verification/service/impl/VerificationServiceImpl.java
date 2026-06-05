package com.gb.member.domain.verification.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.verification.dto.request.VerificationRequest;
import com.gb.member.domain.verification.dto.response.VerificationStatusResponse;
import com.gb.member.domain.verification.dto.response.VerificationSubmitResponse;
import com.gb.member.domain.verification.entity.IdentityDocumentType;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import com.gb.member.domain.verification.repository.UserVerificationRepository;
import com.gb.member.domain.verification.service.VerificationService;
import com.gb.member.global.client.WalletClient;
import com.gb.member.global.exception.code.MemberErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신분증 인증 비즈니스 로직.
 *
 * <p>실 신원확인 API 대신 <b>유형별 번호 형식(정규식) 검증</b>으로 처리한다(데모). 주 신분증인
 * 외국인등록증을 가장 엄격히 본다({@link IdentityDocumentType}). 형식 검증을 통과하면 즉시 승인하고
 * {@link Member#markVerified()}로 인증 배지를 부여한다.
 *
 * <p><b>이슈 #152 — 사이드이펙트:</b> APPROVED 시점에 {@link WalletClient#createWalletFor}로
 * wallet-service에 전자지갑 자동 개설을 위임한다. 호출은 인증 트랜잭션 <b>커밋 후</b>(tx 밖)에 수행하고
 * try/catch로 감싸 <b>fail-open</b>한다 — 외부 HTTP가 쓰기 tx·커넥션을 잡지 않고(11D core-2 계열 분리,
 * self-proxy tx 구조), 지갑 생성에 실패해도 인증은 이미 커밋돼 WARN 로깅만 남는다. 지갑 생성 API가
 * 멱등이라 사용자는 추후 다른 화면에서 재호출/보정 가능하다(보정 로직은 v1.1 별 이슈로 분리).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationServiceImpl implements VerificationService {

    /** 중복 제출을 막을 "유효한 인증" 상태(진행중/승인). REJECTED는 재신청 허용. */
    private static final List<VerificationStatus> ACTIVE_STATUSES =
            List.of(VerificationStatus.PENDING, VerificationStatus.APPROVED);

    private final MemberRepository memberRepository;
    private final UserVerificationRepository verificationRepository;
    private final WalletClient walletClient;

    /** self-injection: submitVerificationTx의 @Transactional 프록시 적용 위함(community/wallet 동일 패턴). */
    @Autowired
    @Lazy
    private VerificationService self;

    @Override
    @Transactional(readOnly = true)
    public VerificationStatusResponse getMyVerification(String userPublicId) {
        Member member = getActiveMemberOrThrow(userPublicId);
        UserVerification verification = verificationRepository
                .findTopByMemberOrderByIdDesc(member)
                // 인증 이력 없음도 일반 미존재로 MEMBER4001 처리(명세 §개선사유 — 임의 코드 신설 대신 재사용).
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        return VerificationStatusResponse.from(verification);
    }

    @Override
    public VerificationSubmitResponse submitVerification(String userPublicId, VerificationRequest request) {
        // DB 본문(검증·저장·배지)을 self-proxy tx 메서드로 묶어 커밋까지 끝낸다. 지갑 개설(외부 HTTP)은
        // tx "밖"에서 — 쓰기 tx·커넥션을 보유한 채 wallet-service 응답을 기다리지 않는다(11D core-2 계열
        // 분리, community createComment·가입 IdP-first와 동일 사상).
        UserVerification verification = self.submitVerificationTx(userPublicId, request);

        // 5) 이슈 #152 — APPROVED 시 wallet-service에 사용자당 1개 지갑 자동 개설을 위임한다(멱등).
        //    인증은 위에서 이미 커밋 확정 — 호출 실패는 결과에 영향 없이 WARN(fail-open)으로만 남고,
        //    지갑은 추후 재요청/보정으로 회복 가능(API spec §10).
        try {
            walletClient.createWalletFor(userPublicId);
        } catch (RuntimeException ex) {
            log.warn("지갑 자동 개설 호출 실패(인증은 정상 commit). user_public_id={}, err={}",
                    userPublicId, ex.getMessage());
        }

        // 응답은 tx 안에서 적재된 기본 필드(status/createdAt)만 사용 — detached 상태여도 LAZY 미접근이라 안전.
        return VerificationSubmitResponse.from(verification);
    }

    @Override
    @Transactional
    public UserVerification submitVerificationTx(String userPublicId, VerificationRequest request) {
        Member member = getActiveMemberOrThrow(userPublicId);

        if (verificationRepository.existsByMemberAndStatusIn(member, ACTIVE_STATUSES)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        // 2) 신분증 유형 파싱(잘못된 enum 값 → COMMON4001). @Pattern 대신 Service 변환(CLAUDE §6).
        IdentityDocumentType documentType = parseDocumentType(request.getIdentityDocumentType());

        if (!documentType.matches(request.getDocumentNumber())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        // 형식 검증 통과 → 즉시 승인(데모). 인증 레코드 저장 + 회원 배지 부여.
        // document_number는 EncryptedStringConverter가 영속 시점에 AES-256-GCM으로 자동 암호화한다.
        String normalizedDocumentNumber = documentType.normalize(request.getDocumentNumber());
        UserVerification verification = UserVerification.approved(
                member, documentType, normalizedDocumentNumber, request.getS3Key());
        // 10D member-verification-1 — 위 existsBy(1)를 동시에 통과한 자기 동시요청 race 백스톱. 가입(MemberServiceImpl,
        //   member-5)과 동일하게 saveAndFlush로 INSERT를 이 지점에 확정하고, 무결성 위반은 generic "이미 존재"
        //   (COMMON4091)로 통일한다. 예외 시 member.markVerified()에 도달하지 않아 배지 중복 부여도 차단된다.
        //   단순 user_id UNIQUE는 'REJECTED 후 재신청 허용'(ACTIVE_STATUSES)과 충돌해 불가 — DB 백스톱은 활성
        //   인증(PENDING/APPROVED)만 묶는 부분 UNIQUE(생성컬럼, bank_accounts WACC-06 선례)가 필요하며 별도
        //   이슈(수동 DDL 동반)로 둔다. DDL 적용 전엔 이 catch가 발동할 제약이 없지만 코드는 무해·선행 가능.
        try {
            verificationRepository.saveAndFlush(verification);
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }
        member.markVerified();

        return verification;
    }

    private IdentityDocumentType parseDocumentType(String raw) {
        try {
            return IdentityDocumentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    /** 탈퇴하지 않은(활성) 회원을 publicId로 조회한다. 없으면 MEMBER4001. */
    private Member getActiveMemberOrThrow(String userPublicId) {
        return memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
