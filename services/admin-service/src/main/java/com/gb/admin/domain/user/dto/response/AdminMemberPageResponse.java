package com.gb.admin.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 회원 페이지")
public record AdminMemberPageResponse(
        @Schema(description = "회원 목록.")
        List<AdminMemberResponse> members,

        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "42") long totalElements,
        @Schema(example = "3") int totalPages
) {

    public static AdminMemberPageResponse from(Page<AdminMemberResponse> page) {
        return new AdminMemberPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
