package com.gb.wallet.domain.transaction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /api/v1/transfers/scheduled/validate 응답 data — 정기 송금 대상 검증 결과.
 *
 * <p>도메인 검증(통화 정합성 등)의 미통과는 200 + {@code is_valid=false} + {@code reason}으로
 * 표현한다. 입력 형식 오류·계좌 미존재·미인증 토큰 등은 200이 아닌 도메인 에러(400/404)로 응답되며
 * 본 DTO와 무관하다.
 *
 * <p>{@link #valid()}/{@link #invalid(String)} 정적 팩토리로 의도를 명확히 한다.
 */
@Schema(description = "정기 송금 대상 검증 결과")
public record ValidateScheduledResponse(

        @Schema(description = "검증 통과 여부", example = "true")
        boolean isValid,

        @Schema(description = "미통과 사유. is_valid=true면 null", example = "null", nullable = true)
        String reason
) {

    /** 통과 — reason 없음. */
    public static ValidateScheduledResponse valid() {
        return new ValidateScheduledResponse(true, null);
    }

    /** 미통과 — 사용자에게 표시할 reason 메시지 포함. */
    public static ValidateScheduledResponse invalid(String reason) {
        return new ValidateScheduledResponse(false, reason);
    }
}
