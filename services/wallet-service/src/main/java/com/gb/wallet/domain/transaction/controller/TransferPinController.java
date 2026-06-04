package com.gb.wallet.domain.transaction.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.dto.request.TransferPinRequest;
import com.gb.wallet.domain.transaction.service.TransferPinService;
import com.gb.wallet.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 송금 PIN(별도 PIN) 설정/검증 API.
 *
 * <p>방식 B라 계정 비밀번호는 우리 DB에 없으므로(IdP 보유), 송금 전 본인확인은 별도 송금 PIN(6자리)으로 한다.
 * 와이어프레임 14-1 "인증 후 송금하기" 클릭 시 {@code /pin-verify}를 먼저 호출하고, 성공해야 송금 생성으로 넘어간다.
 * 사용자 식별은 JWT {@code public_id}({@link CurrentUserPublicId}).
 */
@Tag(name = "Transfer", description = "송금 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transfers")
public class TransferPinController {

    private final TransferPinService transferPinService;

    @PostMapping("/pin")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "송금 PIN 설정",
            description = "송금 시 본인확인용 PIN(숫자 6자리)을 최초 설정한다. 이미 설정돼 있으면 COMMON4091. "
                    + "PIN 변경(기존 PIN 확인 후 교체)은 후속 기능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "PIN 설정 완료."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "COMMON4001 - PIN 형식 오류(숫자 6자리 아님).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "WALLET4001 - 존재하지 않는 지갑입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "COMMON4091 - 이미 송금 PIN이 설정되어 있습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<Void> setPin(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody TransferPinRequest request) {
        transferPinService.setPin(userPublicId, request.getPin());
        return ApiResponse.success(null);
    }

    @PostMapping("/pin-verify")
    @Operation(
            summary = "송금 PIN 검증",
            description = "송금 실행 전 PIN을 검증한다. 성공 시 송금 생성 API로 진행한다. "
                    + "5회 연속 실패 시 10분간 잠긴다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "검증 성공."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "TRANSFER4007 - PIN 불일치 / TRANSFER4009 - PIN 미설정 / COMMON4001 - 형식 오류.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "WALLET4001 - 존재하지 않는 지갑입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "TRANSFER4008 - 입력 횟수 초과로 잠김(10분).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<Void> verifyPin(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody TransferPinRequest request) {
        transferPinService.verifyPin(userPublicId, request.getPin());
        return ApiResponse.success(null);
    }
}
