package com.gb.admin.global.client;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@Profile("mock-clients")
public class MockDocumentAdminClient implements DocumentAdminClient {

    private static final List<AdminDocumentSummary> FIXTURES = List.of(
            new AdminDocumentSummary(
                    "dddddddd-0001-0000-0000-000000000001",
                    "11111111-1111-1111-1111-111111111111", "Nguyen Thi Linh",
                    "LABOR_CONTRACT", "VI", "MEDIUM",
                    "변호사 상담 광고 클릭",
                    LocalDateTime.of(2026, 6, 7, 9, 12, 0)),
            new AdminDocumentSummary(
                    "dddddddd-0001-0000-0000-000000000002",
                    "44444444-4444-4444-4444-444444444444", "Tara Park",
                    "PAYSLIP", "TH", "LOW",
                    "-",
                    LocalDateTime.of(2026, 6, 7, 11, 0, 0))
    );

    @Override
    public DocumentStats stats() {
        // Phase 1 mock — dashboard summary와 일관성 있게 둠.
        return new DocumentStats(348L, 332L, 11L, 5L);
    }

    @Override
    public Page<AdminDocumentSummary> recent(int page, int size) {
        return AdminPage.of(FIXTURES, page, size);
    }
}
