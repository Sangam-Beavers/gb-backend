package com.gb.appadmin.domain.notice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "공지사항 수정 요청 (null 필드는 변경 안 함)")
public record NoticeUpdateRequest(
        @Schema(description = "제목") @Size(max = 200) String title,
        @Schema(description = "본문") String content,
        @Schema(description = "목록 상단 고정") Boolean pinned,
        @Schema(description = "게시 여부") Boolean published
) {}
