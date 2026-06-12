package com.gb.wallet.domain.reward.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.reward.dto.response.CouponListResponse;
import com.gb.wallet.domain.reward.dto.response.StampCardResponse;
import com.gb.wallet.domain.reward.service.RewardService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reward", description = "송금 적립 스탬프/쿠폰 API")
@RestController
@RequestMapping("/api/v1/rewards")
@RequiredArgsConstructor
public class RewardController {

    // 응답별 ErrorResponse 예시 JSON (TransferController와 동일 규칙 — 안 박으면 단일 디폴트로 표시됨).
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";

    private final RewardService rewardService;

    /** 현재 스탬프 카드 진행도 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "스탬프 카드 조회",
            description = "현재 사용자의 송금 적립 스탬프 진행도를 반환한다. current_count/target으로 채워진 칸을, "
                    + "available_coupon_count로 사용 가능한 쿠폰 수를 표시한다. 적립 이력이 없으면 0/target. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 StampCardResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/stamp-card")
    public ApiResponse<StampCardResponse> getStampCard(@CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(rewardService.getStampCard(userPublicId));
    }

    /** 보유 쿠폰 목록 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "보유 쿠폰 목록 조회",
            description = "현재 사용자가 보유한 쿠폰 목록을 발급 최신순으로 반환한다. 보유 쿠폰이 없으면 빈 배열. "
                    + "사용자는 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 CouponListResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/coupons")
    public ApiResponse<CouponListResponse> getCoupons(@CurrentUserPublicId String userPublicId) {
        return ApiResponse.success(rewardService.getCoupons(userPublicId));
    }
}
