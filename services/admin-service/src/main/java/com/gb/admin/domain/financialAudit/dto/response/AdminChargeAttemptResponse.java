package com.gb.admin.domain.financialAudit.dto.response;

import com.gb.admin.global.client.AdminChargeAttempt;
import com.gb.admin.global.client.AdminMemberMini;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "충전 실패 큐 단건(회원 표시 정보 enrich 포함)")
public record AdminChargeAttemptResponse(
        String chargeAttemptPublicId,
        String userPublicId,
        String userEmail,
        String userNickname,
        String amount,
        String currencyCode,
        String status,
        String reason,
        Long bankAccountId,
        LocalDateTime createdAt
) {

    public static AdminChargeAttemptResponse from(AdminChargeAttempt a, AdminMemberMini member) {
        return new AdminChargeAttemptResponse(
                a.chargeAttemptPublicId(),
                a.userPublicId(),
                member != null ? member.email() : "Unknown",
                member != null ? member.nickname() : "Unknown",
                a.amount() == null ? null : a.amount().toPlainString(),
                a.currencyCode(),
                a.status(),
                a.reason(),
                a.bankAccountId(),
                a.createdAt()
        );
    }
}
