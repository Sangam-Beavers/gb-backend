package com.gb.wallet.domain.account.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Getter;

/**
 * {@code POST /api/v1/accounts/confirm} 응답 data.
 *
 * <p>인증 성공 여부를 반환한다. account_token은 서버가 Redis 세션에 저장하며
 * 클라이언트에게 노출하지 않는다 — 이후 {@code POST /accounts}(계좌 등록) 시
 * 서버가 세션에서 직접 소비한다(WACC-05, 10D wallet-account-charge-3).
 */
@Getter
public class ConfirmAccountResponse {

    @Schema(description = "인증 성공 여부. 항상 true(실패 시 에러 응답).", example = "true")
    private final boolean verified;

    @Schema(description = "인증 완료 시각(ISO 8601 UTC)", example = "2026-06-09T12:34:56Z")
    private final String confirmedAt;

    private ConfirmAccountResponse(boolean verified, String confirmedAt) {
        this.verified = verified;
        this.confirmedAt = confirmedAt;
    }

    public static ConfirmAccountResponse success() {
        return new ConfirmAccountResponse(true, Instant.now().toString());
    }
}
