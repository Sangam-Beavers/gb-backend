package com.gb.community.domain.report.controller;

import com.gb.common.response.ApiResponse;
import com.gb.community.domain.report.dto.request.CreateReportRequest;
import com.gb.community.domain.report.dto.response.ReportResponse;
import com.gb.community.domain.report.service.ReportService;
import com.gb.community.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community Report", description = "게시글/댓글 신고 API")
@RestController
@RequestMapping("/api/v1/community")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "게시글 신고",
            description = "특정 게시글을 신고합니다. 동일 게시글 중복 신고 시 409를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "신고 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "COMMUNITY4001 - 존재하지 않는 게시글",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.gb.common.response.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "COMMUNITY4006 - 이미 신고한 콘텐츠",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.gb.common.response.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMUNITY4007 - 지원하지 않는 신고 사유",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.gb.common.response.ErrorResponse.class)))
    })
    @PostMapping("/posts/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportResponse> reportPost(
            @CurrentUserPublicId String reporterPublicId,
            @PathVariable String postId,
            @Valid @RequestBody CreateReportRequest request) {
        return ApiResponse.success(reportService.reportPost(reporterPublicId, postId, request));
    }

    @Operation(summary = "댓글 신고",
            description = "특정 게시글의 댓글을 신고합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "신고 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "COMMUNITY4001 - 게시글 없음 / COMMUNITY4002 - 댓글 없음",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.gb.common.response.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "COMMUNITY4006 - 이미 신고한 콘텐츠",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.gb.common.response.ErrorResponse.class)))
    })
    @PostMapping("/posts/{postId}/comments/{commentId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportResponse> reportComment(
            @CurrentUserPublicId String reporterPublicId,
            @PathVariable String postId,
            @PathVariable String commentId,
            @Valid @RequestBody CreateReportRequest request) {
        return ApiResponse.success(
                reportService.reportComment(reporterPublicId, postId, commentId, request));
    }
}
