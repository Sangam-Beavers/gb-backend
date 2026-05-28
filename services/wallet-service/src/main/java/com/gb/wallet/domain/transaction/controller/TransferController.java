package com.gb.wallet.domain.transaction.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer", description = "송금 관련 API")
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    /** 최근 송금 앱 사용자 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "최근 송금 앱 사용자 조회",
            description = "내가 송신자였던 앱 내부 송금(INTERNAL_TRANSFER, COMPLETED) 기록에서 "
                    + "수신자별 가장 최근 송금 1건씩, 최근순으로 최대 10명을 반환한다. "
                    + "인증 미구현 상태라 현재는 X-User-Public-Id 헤더로 사용자를 식별한다.")
    // responseCode는 HTTP 상태, description에 비즈니스 코드 명시 (잔액 조회 컨트롤러와 동일 규칙).
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. 응답은 공통 ApiResponse로 감싸지며 data에 RecentRecipientsResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "WALLET4001 - 존재하지 않는 지갑(해당 사용자의 지갑 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/recent-recipients/members")
    public ApiResponse<RecentRecipientsResponse> getRecentInternalRecipients(
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId를 추출하도록 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            @RequestHeader("X-User-Public-Id") String userPublicId) {
        return ApiResponse.success(transferService.getRecentInternalRecipients(userPublicId));
    }
}
