package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.AppMemberResponse;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 픽스처 클라이언트. 스프링 컨텍스트 테스트 시 외부 의존 없이 기동.
 * dev 환경에서는 RealMemberAdminClient 가 실제 member-service 를 호출한다.
 */
@Slf4j
@Component
@Profile("test")
public class MockMemberAdminClient implements MemberAdminClient {

    private static final List<AppMemberResponse> FIXTURE = List.of(
            new AppMemberResponse("uid-001", "juan@example.com", "Juan Dela Cruz", "juan123",
                    "PH", "ACTIVE", "APPROVED", false, "2025-12-01T09:00:00"),
            new AppMemberResponse("uid-002", "nguyen@example.com", "Nguyen Van An", "nguyen456",
                    "VN", "ACTIVE", "PENDING", false, "2026-01-15T14:30:00"),
            new AppMemberResponse("uid-003", "siti@example.com", "Siti Rahayu", "siti789",
                    "ID", "SUSPENDED", "APPROVED", true, "2025-11-20T11:00:00"),
            new AppMemberResponse("uid-004", "kim@example.com", "Kim Minji", "minji001",
                    "KH", "ACTIVE", "NOT_SUBMITTED", false, "2026-03-05T08:45:00"),
            new AppMemberResponse("uid-005", "somchai@example.com", "Somchai Jaidee", "somchai02",
                    "TH", "ACTIVE", "REJECTED", false, "2026-02-10T16:20:00")
    );

    @Override
    public AppMemberPageResponse search(String q, String kycStatus, int page, int size) {
        List<AppMemberResponse> filtered = FIXTURE.stream()
                .filter(m -> q == null || q.isBlank()
                        || m.name().contains(q) || m.email().contains(q) || m.nickname().contains(q))
                .filter(m -> kycStatus == null || kycStatus.isBlank() || m.kycStatus().equals(kycStatus))
                .collect(Collectors.toList());
        log.info("[MockMemberAdminClient] search q={}, kycStatus={} → {}건", q, kycStatus, filtered.size());
        return new AppMemberPageResponse(filtered, 0, size, filtered.size(), 1);
    }

    @Override
    public void changeStatus(String userPublicId, String status) {
        log.info("[MockMemberAdminClient] changeStatus userPublicId={}, status={} (픽스처 — 실제 변경 없음)",
                userPublicId, status);
    }

    @Override
    public void setCommunityBan(String userPublicId, boolean banned) {
        log.info("[MockMemberAdminClient] setCommunityBan userPublicId={}, banned={} (픽스처 — 실제 변경 없음)",
                userPublicId, banned);
    }

    @Override
    public Optional<AppMemberResponse> getMemberByPublicId(String userPublicId) {
        log.info("[MockMemberAdminClient] getMemberByPublicId userPublicId={}", userPublicId);
        return FIXTURE.stream().filter(m -> m.userPublicId().equals(userPublicId)).findFirst();
    }
}
