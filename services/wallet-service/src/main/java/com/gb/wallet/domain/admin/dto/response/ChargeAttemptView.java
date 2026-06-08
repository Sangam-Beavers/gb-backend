package com.gb.wallet.domain.admin.dto.response;

import com.gb.wallet.domain.account.entity.ChargeAttempt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "충전 실패 큐 단건")
public record ChargeAttemptView(
        @Schema(description = "idempotency_key를 안정 식별자로 노출 — id 노출 금지 정책 준수.")
        String chargeAttemptPublicId,
        String userPublicId,
        @Schema(description = "충전 시도 금액(String 십진수).")
        String amount,
        String currencyCode,
        @Schema(description = "Phase 1 발표용 고정값 — wallet 본체에 attempt 상태 컬럼 없음. 후속 스프린트에서 분리.")
        String status,
        @Schema(description = "발표용 고정값 — 사유 분리는 다음 스프린트.")
        String reason,
        @Schema(description = "bank_account_id(BIGINT) 내부값. 본체에 bank_account 엔티티 없어 raw Long 노출 — 후속 스프린트에 public_id로 교체.")
        Long bankAccountId,
        LocalDateTime createdAt
) {

    public static ChargeAttemptView from(ChargeAttempt a, String statusFilter) {
        String status = statusFilter != null ? statusFilter : "FAILED";
        return new ChargeAttemptView(
                a.getIdempotencyKey(),
                a.getUserPublicId(),
                a.getAmount() == null ? null : a.getAmount().toPlainString(),
                a.getCurrencyCode() == null ? null : a.getCurrencyCode().name(),
                status,
                "은행 응답 실패(BANK4xx) 또는 timeout",
                a.getBankAccountId(),
                a.getAttemptedAt()
        );
    }
}
