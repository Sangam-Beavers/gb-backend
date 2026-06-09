package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.global.client.dto.VerifyInitResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * {@code POST /api/v1/accounts/verify} 응답 data.
 *
 * <p>1원 소액이체 방식으로 변경됨: account_token을 즉시 반환하는 대신
 * {@code pending: true}와 만료 시각을 반환한다. 클라이언트는 해당 계좌의 입금
 * 적요에서 4자리 인증번호를 확인한 뒤 {@code POST /accounts/confirm}으로 제출해야 한다.
 */
@Getter
public class VerifyAccountResponse {

    @Schema(description = "인증 대기 상태. 항상 true.", example = "true")
    private final boolean pending;

    @Schema(description = "인증 세션 만료 시각(ISO 8601 UTC). 10분 후.", example = "2026-06-09T12:44:56Z")
    private final String expiresAt;

    private VerifyAccountResponse(boolean pending, String expiresAt) {
        this.pending = pending;
        this.expiresAt = expiresAt;
    }

    public static VerifyAccountResponse from(VerifyInitResult result) {
        return new VerifyAccountResponse(result.pending(), result.expiresAt());
    }
}
