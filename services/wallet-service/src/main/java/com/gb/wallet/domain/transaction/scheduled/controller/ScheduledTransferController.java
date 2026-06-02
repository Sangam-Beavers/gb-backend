package com.gb.wallet.domain.transaction.scheduled.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.request.CreateScheduledTransferRequest;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferListResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferResponse;
import com.gb.wallet.domain.transaction.scheduled.service.ScheduledTransferService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정기 송금(scheduled transfer) 컨트롤러. 이번 사이클은 설정({@code POST /scheduled})만.
 * 다음 사이클에서 내역 조회·취소·재개·단건 조회가 추가된다.
 *
 * <p>대상 검증({@code POST /scheduled/validate})은 별도로 {@link
 * com.gb.wallet.domain.transaction.controller.TransferController}에 남아있다 — 향후 본 컨트롤러로
 * 통합 검토(현 사이클은 영향 최소화).
 */
@Tag(name = "Scheduled Transfer", description = "정기 송금 API")
@RestController
@RequestMapping("/api/v1/transfers/scheduled")
@RequiredArgsConstructor
// @Validated: 메서드 파라미터(@RequestParam @Min·@Max 등)의 Bean Validation 활성화.
// 위반 시 ConstraintViolationException → GlobalExceptionHandler에서 COMMON4001(400)으로 변환.
@Validated
public class ScheduledTransferController {

    private final ScheduledTransferService scheduledTransferService;

    /** 정기 송금 설정. 🔒 JWT 필요. 설정 즉시 ACTIVE + next_run_date 계산. */
    @Operation(
            summary = "정기 송금 설정",
            description = "매주/매월 자동 실행되는 정기 송금을 설정한다. 설정 즉시 ACTIVE 상태로 등록되며 "
                    + "다음 실행 예정일(next_run_date)이 KST 기준으로 계산된다. "
                    + "송금 실행 API와 동일하게 transfer_type으로 INTERNAL_TRANSFER/REMITTANCE 분기.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "설정 성공. data에 ScheduledTransferResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다(필수 필드 누락·형식 오류·미지원 frequency) "
                            + "/ TRANSFER4002 - 지원하지 않는 통화 / TRANSFER4003 - 지원하지 않는 송금 유형 "
                            + "/ TRANSFER4004 - 자기 자신에게 송금할 수 없습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT4006 - 인증되지 않은 계좌입니다(REMITTANCE — mock_account_token 미발급).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "ACCOUNT4001 - 존재하지 않는 계좌입니다(REMITTANCE) "
                            + "/ WALLET4001 - 존재하지 않는 지갑입니다(INTERNAL — 수신자 wallet 부재).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "COMMON4221 - 처리할 수 없는 요청입니다 (schedule_day 범위 초과 또는 currency != receive_currency).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScheduledTransferResponse> create(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody CreateScheduledTransferRequest request) {
        ScheduledTransferResponse response = scheduledTransferService.create(userPublicId, request);
        return ApiResponse.success(response, "정기 송금이 설정되었습니다.");
    }

    /** 본인 정기 송금 목록 조회. 🔒 JWT 필요. status로 선택적 필터링 + 페이지네이션. */
    @Operation(
            summary = "정기 송금 목록 조회",
            description = "로그인한 회원 본인이 설정한 정기 송금 목록을 페이지 단위로 조회한다. "
                    + "status 쿼리 파라미터로 ACTIVE/PAUSED/CANCELLED 필터링 가능 (미지정 시 전체). "
                    + "정렬은 created_at DESC(최신 설정 우선). 잘못된 status 값은 COMMON4001.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 ScheduledTransferListResponse(scheduled_transfers 배열 + 페이지 메타)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 값이 올바르지 않습니다 (status 허용 enum 외 / page·size 범위 위반).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<ScheduledTransferListResponse> list(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "0")
            @Min(value = 0, message = "page는 0 이상이어야 합니다") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다")
            @Max(value = 100, message = "size는 100 이하이어야 합니다") int size) {
        return ApiResponse.success(
                scheduledTransferService.list(userPublicId, status, page, size));
    }
}
