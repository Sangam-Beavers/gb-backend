package com.gb.wallet.domain.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/transfers 요청 본문(송금 실행).
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase ↔ snake_case 변환된다 — 필드는 camelCase로 두고
 * {@code @JsonProperty}를 붙이지 않는다.
 *
 * <p>Bean Validation으로 형식만 검증한다. enum 후보값(transferType/currencyCode/receiveCurrencyCode)은
 * {@code @Pattern}으로 박지 않는다 — Service에서 TransactionType.fromCode + ALLOWED 필터 / CurrencyType.fromCode로
 * 검증해 TRANSFER4003·TRANSFER4002·TRANSFER4005로 매핑한다(CLAUDE.md §6).
 *
 * <p>도메인별 <b>조건부 필수</b> 필드(Bean Validation으로 표현이 까다로워 Service의 resolveScopeId에서 검증):
 * <ul>
 *   <li>{@code receiverPublicId}는 INTERNAL_TRANSFER의 수신자 user_public_id — INTERNAL_TRANSFER일 때 필수
 *       (없으면 COMMON4001). REMITTANCE에선 미사용(null 허용). 무조건 {@code @NotBlank}였다면 REMITTANCE가
 *       {@code @Valid}에서 거부돼 HTTP로 도달조차 못 하므로 제거하고 도메인 검증으로 옮겼다(TX1).</li>
 *   <li>{@code bankAccountPublicId}는 REMITTANCE의 수신 은행 계좌 public_id — REMITTANCE일 때 필수
 *       (없으면 COMMON4001). INTERNAL_TRANSFER에선 미사용(null 허용).</li>
 * </ul>
 *
 * <p>클래스 레벨 {@link Schema#example()}: SpringDoc이 전역 Jackson SNAKE_CASE를 무시하고 Java 필드명을
 * camelCase로 박는 버그(TransferFeeRequest 동일 처리) 우회 — Swagger "Try it out" 기본값을 snake_case로 명시.
 */
@Schema(
        description = "송금 실행 요청 (1단계: INTERNAL_TRANSFER + 같은 통화 전용)",
        example = """
                {
                  "transfer_type": "INTERNAL_TRANSFER",
                  "amount": "10000.0000",
                  "currency_code": "KRW",
                  "receive_currency_code": "KRW",
                  "memo": "생활비",
                  "receiver_public_id": "11111111-1111-1111-1111-111111111111"
                }
                """)
public record TransferExecuteRequest(

        @Schema(description = "송금 방식 (1단계는 INTERNAL_TRANSFER만 허용)", example = "INTERNAL_TRANSFER",
                allowableValues = {"INTERNAL_TRANSFER", "REMITTANCE"})
        @NotBlank
        String transferType,

        @Schema(description = "송금 금액 (string 십진수, 정수부 최대 14자리·소수점 최대 4자리, 양수)", example = "10000.0000")
        @NotBlank
        // 양수 십진수만 통과: 0, 0.0, 0.0000 차단(부정형 lookahead). 메시지의 "positive" 계약과 일치.
        // 정수부 ≤14자리 — DECIMAL(18,4) 초과 금액이 500(DataIntegrity)이 아닌 COMMON4001(400)로
        // 떨어지게 상한을 둔다(ChargeRequest @Digits(integer=14)와 동일 기준).
        @Pattern(regexp = "^(?!0+(\\.0{1,4})?$)\\d{1,14}(\\.\\d{1,4})?$",
                message = "amount must be a positive decimal with up to 14 integer and 4 fractional digits")
        String amount,

        @Schema(description = "송금 통화 코드", example = "KRW")
        @NotBlank
        String currencyCode,

        @Schema(description = "수취 통화 코드 (1단계는 currency_code와 같아야 함, 다르면 TRANSFER4005)",
                example = "KRW")
        @NotBlank
        String receiveCurrencyCode,

        @Schema(description = "메모(선택, 최대 255자)", example = "생활비")
        @Size(max = 255)
        String memo,

        // 조건부 필수(INTERNAL_TRANSFER일 때만 필수)라 무조건 @NotBlank를 두지 않는다 — 두면 REMITTANCE가
        // @Valid에서 거부돼 HTTP 도달 불가(TX1). 존재 검증은 Service(resolveScopeId)에서 type별로 한다.
        @Schema(description = "수신자 회원 식별자(UUID, INTERNAL_TRANSFER 시 필수)",
                example = "11111111-1111-1111-1111-111111111111", nullable = true)
        String receiverPublicId,

        @Schema(description = "수신 은행 계좌 식별자(UUID, REMITTANCE 시 필수)",
                example = "22222222-2222-2222-2222-222222222222", nullable = true)
        String bankAccountPublicId
) {
}
