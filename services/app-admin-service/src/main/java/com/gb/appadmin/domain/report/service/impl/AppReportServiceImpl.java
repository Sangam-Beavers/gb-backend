package com.gb.appadmin.domain.report.service.impl;

import com.gb.appadmin.domain.member.dto.response.AppMemberResponse;
import com.gb.appadmin.domain.report.dto.response.MemberReportItemResponse;
import com.gb.appadmin.domain.report.dto.response.MemberReportsResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorPageResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorSummary;
import com.gb.appadmin.domain.report.service.AppReportService;
import com.gb.appadmin.global.client.CommunityAdminClient;
import com.gb.appadmin.global.client.MemberAdminClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppReportServiceImpl implements AppReportService {

    private final CommunityAdminClient communityAdminClient;
    private final MemberAdminClient memberAdminClient;

    @Override
    public ReportedAuthorPageResponse getReportedAuthors(int page, int size) {
        List<ReportedAuthorSummary> raw = communityAdminClient.getReportedAuthors(page, size);

        // member 표시정보 enrich (건별 조회 — demo 규모 가정, 추후 배치 전환 TODO)
        List<ReportedAuthorSummary> enriched = raw.stream()
                .map(s -> {
                    AppMemberResponse m = memberAdminClient
                            .getMemberByPublicId(s.authorPublicId())
                            .orElse(null);
                    String name = m != null ? m.name() : null;
                    String nickname = m != null ? m.nickname() : null;
                    return new ReportedAuthorSummary(
                            s.authorPublicId(), name, nickname,
                            s.totalReportCount(), s.reportedContentCount());
                })
                .toList();

        // 페이지 메타는 community 응답 기준 — raw 리스트 단순 반환이므로 1페이지 처리
        int total = enriched.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        return new ReportedAuthorPageResponse(enriched, page, size, total, Math.max(totalPages, 1));
    }

    @Override
    public MemberReportsResponse getMemberReports(String userPublicId) {
        List<MemberReportItemResponse> reports = communityAdminClient.getMemberReports(userPublicId);

        AppMemberResponse m = memberAdminClient.getMemberByPublicId(userPublicId).orElse(null);
        String name = m != null ? m.name() : null;
        String nickname = m != null ? m.nickname() : null;

        return new MemberReportsResponse(userPublicId, name, nickname, reports);
    }
}
