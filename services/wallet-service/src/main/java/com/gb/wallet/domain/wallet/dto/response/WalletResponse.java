package com.gb.wallet.domain.wallet.dto.response;

import com.gb.wallet.domain.wallet.entity.Wallet;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 지갑 단건 응답 data. (POST/GET 공용 — 이슈 #152)
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * ({@code wallet_public_id}, {@code created_at}). 시각은 ISO 8601 UTC {@code Z} 문자열.
 *
 * <p>POST {@code /api/v1/wallets}의 응답이자 GET 단건 응답으로도 재사용 가능한 최소 형태다.
 * 잔액은 본 응답에 포함하지 않는다 — 잔액은 별도 {@link WalletBalanceResponse}로 조회한다.
 */
@Getter
public class WalletResponse {

    @Schema(description = "전자지갑 식별자(UUID)", example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
    private final String walletPublicId;

    @Schema(description = "지갑 상태", example = "ACTIVE",
            allowableValues = {"ACTIVE", "SUSPENDED", "CLOSED"})
    private final String status;

    @Schema(description = "지갑 생성 시각(ISO 8601, UTC Z)", example = "2026-06-05T04:15:30Z")
    private final String createdAt;

    @Builder
    private WalletResponse(String walletPublicId, String status, String createdAt) {
        this.walletPublicId = walletPublicId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static WalletResponse from(Wallet wallet) {
        return WalletResponse.builder()
                .walletPublicId(wallet.getPublicId())
                .status(wallet.getStatus().name())
                .createdAt(toUtcZ(wallet.getCreatedAt()))
                .build();
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다.
     * 초 단위로 절삭해 명세 mock 포맷("2026-06-05T04:15:30Z")과 일치시킨다.
     */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
