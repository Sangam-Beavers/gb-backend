package com.gb.appadmin.domain.member.dto.response;

import java.util.List;

public record AppMemberPageResponse(
        List<AppMemberResponse> members,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
