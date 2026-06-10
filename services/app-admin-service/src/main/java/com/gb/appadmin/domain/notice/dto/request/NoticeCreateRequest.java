package com.gb.appadmin.domain.notice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "공지사항 등록 요청")
public record NoticeCreateRequest(
        @Schema(description = "제목", example = "서비스 점검 안내")
        @NotBlank @Size(max = 200)
        String title,

        @Schema(description = "본문", example = "2026-06-10 02:00~04:00 서비스 점검이 있을 예정입니다.")
        @NotBlank
        String content,

        @Schema(description = "목록 상단 고정 여부", example = "false")
        Boolean pinned,

        @Schema(description = "즉시 게시 여부", example = "true")
        Boolean published
) {}
