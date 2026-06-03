package com.gb.wallet.domain.transaction.scheduled.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/transfers/scheduled 요청 본문 — 정기 송금 설정.
 *
 * <p>송금 실행({@link com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest}) 및 정기 송금
 * 대상 검증({@link com.gb.wallet.domain.transaction.dto.request.ValidateScheduledRequest})과 동일하게 두
 * 유형(INTERNAL_TRANSFER / REMITTANCE)을 한 엔드포인트에서 {@code transferType}으로 분기한다. 대상 식별자는
 * 유형별 조건부 필수 ({@code receiverPublicId}는 INTERNAL, {@code bankAccountPublicId}는 REMITTANCE).
 *
 * <p>enum 후보값은 {@code @Pattern}으로 박지 않고 Service에서 enum 파싱 + ALLOWED 필터로 검증한다 —
 * 미지원 통화는 TRANSFER4002, 미지원 송금 유형은 TRANSFER4003, 미지원 frequency는 COMMON4001 (또는
 * 도메인 코드 — 본 명세는 명시 안 함). schedule_day 범위 초과는 COMMON4221(422)로 분리(SSOT 정책).
 */
@Schema(
        description = "정기 송금 설정 요청",
        example = """
                {
                  "transfer_type": "REMITTANCE",
                  "bank_account_public_id": "7g8h9i0j-1234-5678-90ab-cdef12345678",
                  "amount": "500000.0000",
                  "currency_code": "KRW",
                  "receive_currency_code": "KRW",
                  "frequency": "MONTHLY",
                  "schedule_day": 25,
                  "memo": "매달 생활비"
                }
                """)
public record CreateScheduledTransferRequest(

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
        // Service에서도 amount.signum() > 0 추가 검증으로 정규식 우회 / 프로그램 경로(Bean Validation 미적용) 방어.
        @Pattern(regexp = "^(?!0+(\\.0{1,4})?$)\\d+(\\.\\d{1,4})?$",
                message = "amount must be a positive decimal with up to 4 fractional digits")
        String amount,

        @Schema(description = "출금 통화 코드", example = "KRW")
        @NotBlank
        String currencyCode,

        @Schema(description = "수취 통화 코드 (1·2단계 same-currency 강제, 다르면 COMMON4221)", example = "KRW")
        @NotBlank
        String receiveCurrencyCode,

        @Schema(description = "반복 주기", example = "MONTHLY",
                allowableValues = {"WEEKLY", "MONTHLY"})
        @NotBlank
        String frequency,

        @Schema(description = "실행 기준일 (MONTHLY=1~31, WEEKLY=1~7 ISO 요일/1=월). 범위 초과 시 COMMON4221.",
                example = "25")
        @NotNull
        Integer scheduleDay,

        @Schema(description = "메모(선택, 최대 255자)", example = "매달 생활비", nullable = true)
        @Size(max = 255)
        String memo
) {
}
