package com.gb.wallet.domain.exchange.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환전 실행 요청. 견적 단계에서 발급받은 {@code quote_public_id}만 보낸다 —
 * 환율·금액·수수료는 견적에 이미 확정돼 Redis에 저장돼 있으므로 다시 받지 않는다.
 */
@Getter
@NoArgsConstructor
public class ExchangeExecuteRequest {

    @Schema(description = "견적 조회에서 발급받은 견적 식별자(UUID)",
            example = "9f8e7d6c-1234-5678-abcd-ef0123456789")
    @NotBlank(message = "견적 식별자는 필수입니다")
    private String quotePublicId;
}
