package com.gb.member.domain.admin.dto.response;

import com.gb.member.domain.admin.util.DocumentNumberMasker;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.verification.entity.IdentityDocumentType;
import com.gb.member.domain.verification.entity.UserVerification;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 관리자 회원 목록의 단건 응답. PII 컬럼(documentNumber)은 절대 평문 노출 금지 — masked 필드만 사용.
 */
@Schema(description = "관리자 회원 뷰(/internal/admin)")
public record AdminMemberView(
        String userPublicId,
        String email,
        String name,
        String nickname,
        String nationality,
        @Schema(description = "KYC 상태(PENDING/APPROVED/REJECTED/NOT_SUBMITTED).")
        String kycStatus,
        @Schema(nullable = true)
        String identityDocumentType,
        @Schema(description = "신분증 번호 마스킹본. 평문 절대 노출 금지(conventions §15 PII).",
                example = "990101-5******", nullable = true)
        String identityDocumentNumberMasked,
        @Schema(description = "가입 시각(UTC).")
        LocalDateTime joinedAt
) {

    public static AdminMemberView from(Member m, UserVerification verification) {
        String kyc = "NOT_SUBMITTED";
        String docType = null;
        String docMasked = null;
        if (verification != null) {
            kyc = verification.getStatus() != null ? verification.getStatus().name() : "PENDING";
            IdentityDocumentType type = verification.getDocumentType();
            docType = type != null ? type.name() : null;
            docMasked = DocumentNumberMasker.mask(type, verification.getDocumentNumber());
        }
        return new AdminMemberView(
                m.getPublicId(),
                m.getEmail(),
                m.getName(),
                m.getNickname(),
                m.getNationality(),
                kyc,
                docType,
                docMasked,
                m.getCreatedAt()
        );
    }
}
