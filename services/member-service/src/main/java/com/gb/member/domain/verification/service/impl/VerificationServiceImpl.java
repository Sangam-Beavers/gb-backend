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
import com.gb.member.global.exception.code.MemberErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신분증 인증 비즈니스 로직.
 *
 * <p>실 신원확인 API 대신 <b>유형별 번호 형식(정규식) 검증</b>으로 처리한다(데모). 주 신분증인
 * 외국인등록증을 가장 엄격히 본다({@link IdentityDocumentType}). 형식 검증을 통과하면 즉시 승인하고
 * {@link Member#markVerified()}로 인증 배지를 부여한다.
 */
@Service
@RequiredArgsConstructor
public class VerificationServiceImpl implements VerificationService {

    /** 중복 제출을 막을 "유효한 인증" 상태(진행중/승인). REJECTED는 재신청 허용. */
    private static final List<VerificationStatus> ACTIVE_STATUSES =
            List.of(VerificationStatus.PENDING, VerificationStatus.APPROVED);

    private final MemberRepository memberRepository;
    private final UserVerificationRepository verificationRepository;

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
    @Transactional
    public VerificationSubmitResponse submitVerification(String userPublicId, VerificationRequest request) {
        Member member = getActiveMemberOrThrow(userPublicId);

        // 1) 이미 진행중(PENDING)·승인(APPROVED) 인증이 있으면 중복 제출 거절.
        if (verificationRepository.existsByMemberAndStatusIn(member, ACTIVE_STATUSES)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_ALREADY_EXISTS);
        }

        // 2) 신분증 유형 파싱(잘못된 enum 값 → COMMON4001). @Pattern 대신 Service 변환(CLAUDE §6).
        IdentityDocumentType documentType = parseDocumentType(request.getIdentityDocumentType());

        // 3) 유형별 번호 형식(정규식) 검증. 외국인등록증이 주 대상. 불일치 → COMMON4001.
        if (!documentType.matches(request.getDocumentNumber())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        // 4) 형식 검증 통과 → 즉시 승인(데모). 인증 레코드 저장 + 회원 배지 부여(dirty checking).
        //    document_number는 엔티티의 EncryptedStringConverter가 영속 시점에 AES-256-GCM으로 자동 암호화한다.
        UserVerification verification = UserVerification.approved(
                member, documentType, request.getDocumentNumber(), request.getS3Key());
        verificationRepository.save(verification);
        member.markVerified();

        return VerificationSubmitResponse.from(verification);
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
