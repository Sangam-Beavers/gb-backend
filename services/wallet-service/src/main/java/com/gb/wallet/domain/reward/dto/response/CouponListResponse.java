package com.gb.wallet.domain.reward.dto.response;

import com.gb.wallet.domain.reward.entity.Coupon;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/rewards/coupons 응답 data — 보유 쿠폰 목록(발급 최신순).
 *
 * <p>시각은 ISO 8601 UTC Z 문자열로 직렬화한다(컨벤션 §5). 내부 id는 노출하지 않고 public_id만 노출한다.
 */
@Getter
public class CouponListResponse {

    @Schema(description = "보유 쿠폰 목록(발급 최신순)")
    private final List<CouponItem> coupons;

    private CouponListResponse(List<CouponItem> coupons) {
        this.coupons = coupons;
    }

    public static CouponListResponse from(List<Coupon> coupons) {
        List<CouponItem> items = coupons.stream().map(CouponItem::from).toList();
        return new CouponListResponse(items);
    }

    /** LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다(초 단위 절삭). 다른 응답 DTO와 동일 규칙. */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Getter
    public static class CouponItem {

        @Schema(description = "쿠폰 public_id(UUID)", example = "9b2f1c7e-1a2b-3c4d-5e6f-7a8b9c0d1e2f")
        private final String publicId;

        @Schema(description = "쿠폰 종류", example = "TRANSFER_FEE_FREE", allowableValues = {"TRANSFER_FEE_FREE"})
        private final String type;

        @Schema(description = "쿠폰 상태", example = "ISSUED", allowableValues = {"ISSUED", "USED", "EXPIRED"})
        private final String status;

        @Schema(description = "발급 시각(ISO 8601, UTC Z)", example = "2026-06-11T04:15:30Z")
        private final String issuedAt;

        @Schema(description = "만료 시각(ISO 8601, UTC Z)", example = "2026-09-09T04:15:30Z")
        private final String expiresAt;

        @Builder
        private CouponItem(String publicId, String type, String status, String issuedAt, String expiresAt) {
            this.publicId = publicId;
            this.type = type;
            this.status = status;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        private static CouponItem from(Coupon coupon) {
            return CouponItem.builder()
                    .publicId(coupon.getPublicId())
                    .type(coupon.getType().name())
                    .status(coupon.getStatus().name())
                    .issuedAt(toUtcZ(coupon.getIssuedAt()))
                    .expiresAt(toUtcZ(coupon.getExpiresAt()))
                    .build();
        }
    }
}
