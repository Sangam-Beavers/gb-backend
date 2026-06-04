package com.gb.wallet.domain.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/transfers/scheduled/validate 요청 본문 — 정기 송금 대상 유효성 사전 검증.
 *
 * <p>정기 송금 설정 화면에서 본 설정 전에 (수취 대상, 금액, 통화 조합)이 정합한지 확인하는 사전 검증
 * 용도다. 검증 자체가 성공하면 200, 통과 여부는 응답 {@code is_valid}로 반환된다(미통과 사유는
 * {@code reason}). 입력 형식 오류·계좌 미존재·미인증 토큰 등은 200이 아닌 도메인 에러(400/404)다.
 *
 * <p>송금 실행({@link TransferExecuteRequest})과 동일 패턴 — 두 유형(INTERNAL_TRANSFER / REMITTANCE)을
 * 한 엔드포인트에서 {@code transferType}으로 분기하며, 대상 식별자는 유형별로 조건부 필수
 * ({@code receiverPublicId}는 INTERNAL 필수, {@code bankAccountPublicId}는 REMITTANCE 필수). 조건부
 * 필수는 Bean Validation으로 표현이 까다로워 Service에서 검증한다(없으면 COMMON4001).
 *
 * <p>enum 후보값({@code transferType}/{@code currencyCode}/{@code receiveCurrencyCode})은
 * {@code @Pattern}으로 박지 않는다 — Service에서 enum 파싱 + ALLOWED 필터로 검증해 TRANSFER4002·4003
 * 으로 매핑한다(CLAUDE.md §6).
 */
@Schema(
        description = "정기 송금 대상 유효성 검증 요청",
        example = """
                {
                  "transfer_type": "REMITTANCE",
                  "bank_account_public_id": "7g8h9i0j-1234-5678-90ab-cdef12345678",
                  "amount": "500000.0000",
                  "currency_code": "KRW",
                  "receive_currency_code": "KRW"
                }
                """)
public record ValidateScheduledRequest(

        @Schema(description = "송금 방식", example = "REMITTANCE",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        @NotBlank
        String transferType,

        @Schema(description = "수신자 회원 식별자(UUID, INTERNAL_TRANSFER 필수)",
                example = "11111111-1111-1111-1111-111111111111", nullable = true)
        String receiverPublicId,

        @Schema(description = "수신 은행 계좌 식별자(UUID, REMITTANCE 필수)",
                example = "7g8h9i0j-1234-5678-90ab-cdef12345678", nullable = true)
        String bankAccountPublicId,

        @Schema(description = "회차당 송금액 (string 십진수, 소수점 최대 4자리, 양수)", example = "500000.0000")
        @NotBlank
        // 양수 십진수만 통과: 0, 0.0, 0.0000 차단(부정형 lookahead). 메시지의 "positive" 계약과 일치.
        @Pattern(regexp = "^(?!0+(\\.0{1,4})?$)\\d+(\\.\\d{1,4})?$",
                message = "amount must be a positive decimal with up to 4 fractional digits")
        String amount,

        @Schema(description = "출금 통화 코드", example = "KRW")
        @NotBlank
        String currencyCode,

        @Schema(description = "수취 통화 코드 (1·2단계 same-currency 강제 — 다르면 is_valid=false + reason)",
                example = "KRW")
        @NotBlank
        String receiveCurrencyCode
) {
}
