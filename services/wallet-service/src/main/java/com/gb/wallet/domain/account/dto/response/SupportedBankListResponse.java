package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.domain.account.entity.Bank;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * GET /api/v1/accounts/supported-banks 응답 data.
 *
 * <p>JSON 필드명은 <b>전역</b> Jackson 설정으로 변환된다
 * (application.yaml: {@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 * 따라서 필드는 camelCase로 두고 {@code @JsonProperty}를 붙이지 않는다
 * (bankCode → bank_code 자동 변환).
 */
@Getter
public class SupportedBankListResponse {

    @Schema(description = "지원 은행 목록 (활성화된 국내 은행, 이름 가나다순)")
    private final List<SupportedBankResponse> banks;

    private SupportedBankListResponse(List<SupportedBankResponse> banks) {
        this.banks = banks;
    }

    public static SupportedBankListResponse from(List<Bank> banks) {
        return new SupportedBankListResponse(
                banks.stream().map(SupportedBankResponse::from).toList());
    }
}
