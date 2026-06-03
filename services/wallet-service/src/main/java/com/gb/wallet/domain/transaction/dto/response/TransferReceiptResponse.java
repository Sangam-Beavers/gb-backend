package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * GET /api/v1/transfers/{id}/receipt 응답 data — 송금 한 건의 확인증.
 *
 * <p>대상 거래: INTERNAL_TRANSFER · REMITTANCE. 충전·환전·기타 유형은 컨트롤러에서 차단된다.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환 — 필드는 camelCase로 두고
 * {@code @JsonProperty}를 붙이지 않는다. 금액·환율은 명세상 string 십진수로 직렬화한다.
 *
 * <p><b>nullable 필드 — 도메인별 다름:</b>
 * <ul>
 *   <li>{@code receiverName} — INTERNAL은 MemberClient 응답의 nickname(외부 장애 시 null),
 *       REMITTANCE는 {@link BankAccount#getHolderName()} snapshot(컬럼 추가 전 등록 계좌면 null).</li>
 *   <li>{@code bankName} · {@code accountNumber} — REMITTANCE만 값 있음. INTERNAL은 외부 계좌가
 *       없으므로 둘 다 {@code null}.</li>
 *   <li>{@code exchangeRate} — 1·2단계 same-currency 강제라 항상 {@code null}. 3단계(다통화) 도입 시
 *       값을 채운다.</li>
 * </ul>
 *
 * <p>{@code accountNumber}는 평문 저장이지만 응답 직전에 {@link AccountNumberMasker}로 마스킹한다.
 */
@Schema(description = "송금 확인증")
public record TransferReceiptResponse(

        @Schema(description = "거래 식별자(UUID, public_id)",
                example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
        String publicId,

        @Schema(description = "송금인 닉네임(요청자 본인). 본인 확인증이므로 호출 시점 user_public_id로 풀어 표시할 수도 있으나, "
                + "송금 당시 정보 보존을 위해 거래 시점 snapshot 가능. 현재 구현은 호출 시 sender 닉네임 fetch.",
                example = "Linh")
        String senderName,

        @Schema(description = "수취인명. INTERNAL은 수신자 닉네임, REMITTANCE는 등록 시 verify 응답의 예금주명. "
                + "외부 장애·구 계좌 등으로 null 가능", example = "NGUYEN VAN A", nullable = true)
        String receiverName,

        @Schema(description = "수취 은행명. REMITTANCE만 값 있음, INTERNAL은 null",
                example = "Quokka Bank", nullable = true)
        String bankName,

        @Schema(description = "수취 계좌번호(마스킹). REMITTANCE만 값 있음, INTERNAL은 null",
                example = "****1234", nullable = true)
        String accountNumber,

        @Schema(description = "송금 금액 (string 십진수, 소수점 4자리)", example = "500000.0000")
        String amount,

        @Schema(description = "송금 통화 코드", example = "KRW")
        String currencyCode,

        @Schema(description = "송금 수수료 (string 십진수, 소수점 4자리)", example = "3000.0000")
        String fee,

        @Schema(description = "적용 환율 (1·2단계 same-currency는 항상 null, 3단계 다통화부터 값)",
                example = "18.0250", nullable = true)
        String exchangeRate,

        @Schema(description = "수취 금액 (1·2단계는 amount와 동일, string 십진수)", example = "500000.0000")
        String receiveAmount,

        @Schema(description = "수취 통화 코드", example = "KRW")
        String receiveCurrencyCode,

        @Schema(description = "거래 상태", example = "COMPLETED")
        String status,

        @Schema(description = "송금 시각(ISO 8601, UTC Z)", example = "2026-05-25T12:00:00Z")
        String createdAt
) {

    /**
     * Persisted {@link Transaction}과 (REMITTANCE인 경우의) {@link BankAccount}를 응답 DTO로 변환한다.
     * INTERNAL_TRANSFER는 {@code bankAccount}로 {@code null}을 넘긴다.
     */
    public static TransferReceiptResponse of(Transaction tx, String senderName, BankAccount bankAccount) {
        String bankName = bankAccount != null && bankAccount.getBank() != null
                ? bankAccount.getBank().getName()
                : null;
        String maskedAccountNumber = bankAccount != null
                ? AccountNumberMasker.mask(bankAccount.getAccountNumber())
                : null;
        return new TransferReceiptResponse(
                tx.getPublicId(),
                senderName,
                tx.getReceiverName(),
                bankName,
                maskedAccountNumber,
                scaledString(tx.getAmount()),
                tx.getCurrencyCode().name(),
                scaledString(tx.getFee()),
                tx.getExchangeRate() != null
                        ? tx.getExchangeRate().setScale(8, RoundingMode.HALF_UP).toPlainString()
                        : null,
                scaledString(tx.getReceiveAmount()),
                tx.getReceiveCurrencyCode() != null ? tx.getReceiveCurrencyCode().name() : null,
                tx.getStatus().name(),
                toUtcZ(tx.getCreatedAt())
        );
    }

    private static String scaledString(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
