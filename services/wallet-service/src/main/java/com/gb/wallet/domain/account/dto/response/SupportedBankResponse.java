package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.domain.account.entity.Bank;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 지원 은행 목록의 단일 항목.
 * snake_case 변환 규칙은 컨테이너 DTO({@link SupportedBankListResponse}) javadoc 참고.
 */
@Getter
public class SupportedBankResponse {

    @Schema(description = "은행 코드", example = "004")
    private final String bankCode;

    @Schema(description = "은행명", example = "KB국민은행")
    private final String bankName;

    @Schema(description = "국가 코드 (ISO 3166-1 alpha-2)", example = "KR")
    private final String country;

    @Builder
    private SupportedBankResponse(String bankCode, String bankName, String country) {
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.country = country;
    }

    public static SupportedBankResponse from(Bank bank) {
        return SupportedBankResponse.builder()
                .bankCode(bank.getCode())
                .bankName(bank.getName())
                .country(bank.getCountry())
                .build();
    }
}
