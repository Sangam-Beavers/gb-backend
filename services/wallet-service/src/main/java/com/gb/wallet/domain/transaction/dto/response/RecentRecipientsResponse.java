package com.gb.wallet.domain.transaction.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/transfers/recent-recipients/members 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * (application.yaml: {@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 * 따라서 DTO 필드는 camelCase로 두고 {@code @JsonProperty}는 붙이지 않는다.
 *
 * <p>명세 §0에 따라 시각({@code lastTransferredAt})은 ISO 8601 UTC Z 문자열로 직렬화한다.
 */
@Getter
public class RecentRecipientsResponse {

    @Schema(description = "최근 송금 수신자 목록 (수신자별 최신 송금 1건씩, 최근순)")
    private final List<RecipientItem> receivers;

    private RecentRecipientsResponse(List<RecipientItem> receivers) {
        this.receivers = receivers;
    }

    public static RecentRecipientsResponse of(List<RecipientItem> receivers) {
        return new RecentRecipientsResponse(receivers);
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다. 초 단위로 절삭한다.
     * 잔액 조회 응답({@code WalletBalanceResponse#toUtcZ})과 동일한 규칙.
     */
    public static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class RecipientItem {

        @Schema(description = "수신자 회원 식별자(UUID)", example = "11111111-1111-1111-1111-111111111111")
        private final String memberPublicId;

        @Schema(description = "수신자 닉네임", example = "Linh")
        private final String nickname;

        @Schema(description = "국적 코드", example = "VN")
        private final String nationality;

        // primitive boolean + 필드명 isXxx 조합은 Lombok이 isXxx() 게터를 만들고 Jackson이 'is'를 떼
        // property를 'verified'로 추출하므로, 전역 SNAKE_CASE 변환을 거쳐도 명세("is_verified")와
        // 어긋난다. 이 케이스는 @JsonProperty로 명시 고정한다(전역 변환의 사각지대 — 예외적 사용).
        @Schema(description = "회원 인증 배지 여부", example = "true")
        @JsonProperty("is_verified")
        private final boolean isVerified;

        @Schema(description = "이웃 온도 등급", example = "GREEN",
                allowableValues = {"RED", "YELLOW", "GREEN", "PURPLE", "BLUE"})
        private final String temperatureGrade;

        @Schema(description = "가장 최근 송금의 통화 코드", example = "VND",
                allowableValues = {"KRW", "USD", "PHP", "VND"})
        private final String lastCurrencyCode;

        @Schema(description = "가장 최근 송금 시각(ISO 8601, UTC Z)", example = "2026-05-24T09:20:00Z")
        private final String lastTransferredAt;

        @Builder
        private RecipientItem(String memberPublicId, String nickname, String nationality,
                              boolean isVerified, String temperatureGrade,
                              String lastCurrencyCode, String lastTransferredAt) {
            this.memberPublicId = memberPublicId;
            this.nickname = nickname;
            this.nationality = nationality;
            this.isVerified = isVerified;
            this.temperatureGrade = temperatureGrade;
            this.lastCurrencyCode = lastCurrencyCode;
            this.lastTransferredAt = lastTransferredAt;
        }
    }
}
