package com.gb.appadmin.domain.notice.controller;

import com.gb.appadmin.domain.notice.dto.response.NoticeResponse;
import com.gb.appadmin.domain.notice.service.NoticeService;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지사항 공개 엔드포인트 — 앱 사용자용(게시된 항목만).
 */
@Tag(name = "Notices (Public)", description = "공지사항 공개 조회")
@RestController
@RequestMapping("/api/v1/app/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지사항 목록", description = "게시된(published=true) 공지사항 목록. 고정 항목 먼저, 최신순 정렬.")
    @GetMapping
    public ApiResponse<List<NoticeResponse>> listPublished() {
        return ApiResponse.success(noticeService.listPublished());
    }

    @Operation(summary = "공지사항 상세")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4002 - 존재하지 않는 공지사항입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{publicId}")
    public ApiResponse<NoticeResponse> getOne(@PathVariable String publicId) {
        return ApiResponse.success(noticeService.getByPublicId(publicId));
    }
}
