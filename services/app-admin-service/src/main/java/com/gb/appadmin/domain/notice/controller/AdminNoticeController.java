package com.gb.appadmin.domain.notice.controller;

import com.gb.appadmin.domain.notice.dto.request.NoticeCreateRequest;
import com.gb.appadmin.domain.notice.dto.request.NoticeUpdateRequest;
import com.gb.appadmin.domain.notice.dto.response.NoticeResponse;
import com.gb.appadmin.domain.notice.service.NoticeService;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지사항 관리자 엔드포인트 — CRUD.
 */
@Tag(name = "Notices (Admin)", description = "공지사항 관리")
@RestController
@RequestMapping("/api/v1/app-admin/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지사항 전체 목록(관리자)", description = "초안 포함 전체 목록 조회.")
    @GetMapping
    public ApiResponse<List<NoticeResponse>> listAll() {
        return ApiResponse.success(noticeService.listAll());
    }

    @Operation(summary = "공지사항 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoticeResponse> create(@Valid @RequestBody NoticeCreateRequest request) {
        return ApiResponse.success(noticeService.create(request));
    }

    @Operation(summary = "공지사항 수정")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4002 - 존재하지 않는 공지사항입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{publicId}")
    public ApiResponse<NoticeResponse> update(@PathVariable String publicId,
                                              @Valid @RequestBody NoticeUpdateRequest request) {
        return ApiResponse.success(noticeService.update(publicId, request));
    }

    @Operation(summary = "공지사항 삭제")
    @DeleteMapping("/{publicId}")
    public ApiResponse<Void> delete(@PathVariable String publicId) {
        noticeService.delete(publicId);
        return ApiResponse.success(null);
    }
}
