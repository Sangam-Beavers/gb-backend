package com.gb.appadmin.domain.report.dto.response;

import java.util.List;

public record ReportedAuthorPageResponse(
        List<ReportedAuthorSummary> authors,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
