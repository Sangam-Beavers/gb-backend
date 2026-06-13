package com.gb.admin.global.client;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 개발/발표용 Mock — wallet의 {@code MockMemberClient} fixture 패턴 그대로.
 *
 * <p>{@code @Profile("dev")}로 등록되므로 stage/prod에서는 빈이 없다(다음 스프린트에서
 * {@code RealMemberAdminClient}=!dev & !test 활성화). test 프로파일은 {@code @MockitoBean}으로 가린다.
 */
@Slf4j
@Component
@Profile("mock-clients")
public class MockMemberAdminClient implements MemberAdminClient {

    /** 위 Mockup 이미지와 일관성 있는 fixture(linh/chen 등 KYC 대기 표시용). */
    private static final List<AdminMemberSummary> FIXTURES = List.of(
            new AdminMemberSummary(
                    "11111111-1111-1111-1111-111111111111",
                    "linh@gb.com", "Nguyen Thi Linh", "Linh", "VN",
                    KycStatus.MATCHED, "ALIEN_REGISTRATION",
                    LocalDateTime.of(2026, 5, 20, 9, 12, 0)),
            new AdminMemberSummary(
                    "22222222-2222-2222-2222-222222222222",
                    "chen@gb.com", "Chen Wei", "Wei", "CN",
                    KycStatus.NEEDS_REVIEW, "NATIONAL_ID",
                    LocalDateTime.of(2026, 5, 22, 11, 4, 0)),
            new AdminMemberSummary(
                    "33333333-3333-3333-3333-333333333333",
                    "minh@gb.com", "Tran Van Minh", "Minh", "VN",
                    KycStatus.APPROVED, "PASSPORT",
                    LocalDateTime.of(2026, 5, 18, 14, 30, 0)),
            new AdminMemberSummary(
                    "44444444-4444-4444-4444-444444444444",
                    "tara@gb.com", "Tara Park", "Tara", "PH",
                    KycStatus.APPROVED, "ALIEN_REGISTRATION",
                    LocalDateTime.of(2026, 5, 15, 10, 0, 0))
    );

    @Override
    public Page<AdminMemberSummary> search(String q, KycStatus kycStatus, int page, int size) {
        List<AdminMemberSummary> filtered = new ArrayList<>(FIXTURES);
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase();
            filtered.removeIf(m -> !match(m, needle));
        }
        if (kycStatus != null) {
            filtered.removeIf(m -> m.kycStatus() != kycStatus);
        }
        return AdminPage.of(filtered, page, size);
    }

    private static boolean match(AdminMemberSummary m, String needle) {
        return (m.email() != null && m.email().toLowerCase().contains(needle))
                || (m.name() != null && m.name().toLowerCase().contains(needle))
                || (m.nickname() != null && m.nickname().toLowerCase().contains(needle));
    }

    @Override
    public void approveKyc(String userPublicId, String adminPublicId) {
        // Phase 1 mock — 실제 status 변경은 member-service /internal/admin 도입 후. 로그만 남김.
        log.info("[MockMemberAdminClient] approveKyc 시뮬레이션: user_public_id={}, admin={}, at={}",
                userPublicId, adminPublicId, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public void rejectKyc(String userPublicId, String adminPublicId, String reason) {
        log.info("[MockMemberAdminClient] rejectKyc 시뮬레이션: user_public_id={}, admin={}, reason={}, at={}",
                userPublicId, adminPublicId, reason, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public Map<String, AdminMemberMini> lookup(Collection<String> userPublicIds) {
        Map<String, AdminMemberMini> result = new LinkedHashMap<>();
        if (userPublicIds == null) return result;
        for (String id : userPublicIds) {
            FIXTURES.stream()
                    .filter(m -> m.userPublicId().equals(id))
                    .findFirst()
                    .ifPresent(m -> result.put(m.userPublicId(),
                            new AdminMemberMini(m.userPublicId(), m.email(), m.nickname(), m.nationality())));
        }
        return result;
    }

    @Override
    public AdminMemberStats stats() {
        return new AdminMemberStats(12430L, 25L, 4L, 1L, "0.8000", 38L);
    }

    @Override
    public AdminMemberDemographics demographics() {
        // 발표용 fixture — 외국인 근로자 플랫폼 특성을 반영한 분포(남성 다수, 20~30대 중심, 동남아 국적 상위).
        return new AdminMemberDemographics(
                List.of(
                        new AdminMemberDemographics.Bucket("MALE", 7890L),
                        new AdminMemberDemographics.Bucket("FEMALE", 4540L)),
                List.of(
                        new AdminMemberDemographics.Bucket("TEENS", 210L),
                        new AdminMemberDemographics.Bucket("TWENTIES", 5120L),
                        new AdminMemberDemographics.Bucket("THIRTIES", 4380L),
                        new AdminMemberDemographics.Bucket("FORTIES", 1860L),
                        new AdminMemberDemographics.Bucket("FIFTIES", 690L),
                        new AdminMemberDemographics.Bucket("SIXTIES_PLUS", 170L)),
                List.of(
                        new AdminMemberDemographics.Bucket("VN", 4120L),
                        new AdminMemberDemographics.Bucket("CN", 2980L),
                        new AdminMemberDemographics.Bucket("PH", 1840L),
                        new AdminMemberDemographics.Bucket("KH", 1230L),
                        new AdminMemberDemographics.Bucket("NP", 980L),
                        new AdminMemberDemographics.Bucket("TH", 760L),
                        new AdminMemberDemographics.Bucket("ID", 520L)));
    }
}
