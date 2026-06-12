package com.gb.wallet.domain.reward.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * GET /api/v1/rewards/stamp-card 응답 data — 현재 스탬프 카드 진행도.
 *
 * <p>프론트의 하드코딩 스탬프 카드(TransferCompletePage)를 대체한다. {@code currentCount}/{@code target}로
 * 채워진 칸 수를, {@code availableCouponCount}로 사용 가능한 쿠폰 보유 수를 표시한다.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다.
 */
@Getter
public class StampCardResponse {

    @Schema(description = "현재 카드에서 채워진 스탬프 수(누적 스탬프 % 목표 개수)", example = "3")
    private final int currentCount;

    @Schema(description = "쿠폰 1장에 필요한 스탬프 수(카드 한 장의 칸 수)", example = "5")
    private final int target;

    @Schema(description = "누적 적립 스탬프 총합(모든 사이클 포함)", example = "8")
    private final long totalStamps;

    @Schema(description = "사용 가능한(발급·미사용) 쿠폰 수", example = "1")
    private final long availableCouponCount;

    private StampCardResponse(int currentCount, int target, long totalStamps, long availableCouponCount) {
        this.currentCount = currentCount;
        this.target = target;
        this.totalStamps = totalStamps;
        this.availableCouponCount = availableCouponCount;
    }

    public static StampCardResponse of(long totalStamps, int target, long availableCouponCount) {
        // 현재 카드 진행도 = 누적 % 목표. target은 1 이상 보장(RewardProperties).
        int currentCount = (int) (totalStamps % target);
        return new StampCardResponse(currentCount, target, totalStamps, availableCouponCount);
    }
}
