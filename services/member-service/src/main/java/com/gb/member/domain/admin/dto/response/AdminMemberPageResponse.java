package com.gb.member.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "관리자 회원 페이지")
public record AdminMemberPageResponse(
        List<AdminMemberView> members,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminMemberPageResponse from(Page<AdminMemberView> p) {
        return new AdminMemberPageResponse(
                p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
