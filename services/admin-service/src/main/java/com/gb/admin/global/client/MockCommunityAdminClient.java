package com.gb.admin.global.client;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("mock-clients")
public class MockCommunityAdminClient implements CommunityAdminClient {

    private static final List<AdminReportSummary> FIXTURES = List.of(
            new AdminReportSummary(
                    "cccccccc-0001-0000-0000-000000000001",
                    "부적절한 구인 광고",
                    "77777777-7777-7777-7777-777777777777", "unknown77",
                    8L, "SPAM", "POST", "PENDING",
                    LocalDateTime.of(2026, 6, 7, 8, 0, 0)),
            new AdminReportSummary(
                    "cccccccc-0001-0000-0000-000000000002",
                    "욕설 댓글 모음",
                    "22222222-2222-2222-2222-222222222222", "user02",
                    4L, "ABUSE", "COMMENT", "PENDING",
                    LocalDateTime.of(2026, 6, 7, 9, 30, 0)),
            new AdminReportSummary(
                    "cccccccc-0001-0000-0000-000000000003",
                    "성적 게시물",
                    "33333333-3333-3333-3333-333333333333", "user03",
                    6L, "SEXUAL", "POST", "PENDING",
                    LocalDateTime.of(2026, 6, 8, 10, 0, 0))
    );

    @Override
    public Page<AdminReportSummary> reports(String status, String reason, int page, int size) {
        List<AdminReportSummary> filtered = new ArrayList<>(FIXTURES);
        if (status != null && !status.isBlank()) {
            filtered.removeIf(r -> !r.status().equalsIgnoreCase(status));
        }
        if (reason != null && !reason.isBlank()) {
            filtered.removeIf(r -> !r.reason().equalsIgnoreCase(reason));
        }
        return AdminPage.of(filtered, page, size);
    }

    @Override
    public void hidePost(String postPublicId, String adminPublicId) {
        log.info("[MockCommunityAdminClient] hidePost 시뮬레이션: post={}, admin={}, at={}",
                postPublicId, adminPublicId, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public void deletePost(String postPublicId, String adminPublicId) {
        log.info("[MockCommunityAdminClient] deletePost 시뮬레이션: post={}, admin={}, at={}",
                postPublicId, adminPublicId, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public long pendingReportCount() {
        return 17L;
    }
}
