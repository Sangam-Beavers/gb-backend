package com.gb.appadmin.domain.report.dto.response;

import java.util.List;

public record MemberReportsResponse(
        String authorPublicId,
        String name,
        String nickname,
        List<MemberReportItemResponse> reports
) {}
