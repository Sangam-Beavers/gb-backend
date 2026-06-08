package com.gb.admin.domain.dashboard.service.impl;

import com.gb.admin.domain.dashboard.dto.response.DashboardAlertsResponse;
import com.gb.admin.domain.dashboard.dto.response.DashboardAlertsResponse.Alert;
import com.gb.admin.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.gb.admin.domain.dashboard.service.DashboardService;
import com.gb.admin.global.client.AdminMemberStats;
import com.gb.admin.global.client.AdminWalletStats;
import com.gb.admin.global.client.CommunityAdminClient;
import com.gb.admin.global.client.DocumentAdminClient;
import com.gb.admin.global.client.DocumentStats;
import com.gb.admin.global.client.MemberAdminClient;
import com.gb.admin.global.client.WalletAdminClient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 — Real client 4종을 합쳐 실 집계로 응답한다(/api/v1/admin/dashboard/summary).
 *
 * <p>각 client 호출 실패는 fail-open 으로 흡수해 대시보드가 멈추지 않게 한다(부분 표시).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private final WalletAdminClient walletAdminClient;
    private final MemberAdminClient memberAdminClient;
    private final DocumentAdminClient documentAdminClient;
    private final CommunityAdminClient communityAdminClient;

    @Override
    public DashboardSummaryResponse getSummary() {
        AdminWalletStats walletStats = safeWalletStats();
        AdminMemberStats memberStats = safeMemberStats();
        DocumentStats docStats = safeDocStats();
        long communityPending = safeCommunityPending();

        Map<String, String> totals = new LinkedHashMap<>();
        if (walletStats != null && walletStats.todayTransactionsTotal() != null) {
            for (Map.Entry<String, BigDecimal> e : walletStats.todayTransactionsTotal().entrySet()) {
                totals.put(e.getKey(), e.getValue() == null ? "0.0000" : e.getValue().toPlainString());
            }
        }
        if (totals.isEmpty()) {
            // 최소 표시 안정성: 키 순서 고정.
            totals.put("KRW", "0.0000");
            totals.put("USD", "0.0000");
            totals.put("VND", "0.0000");
        }

        Map<String, Long> queues = new LinkedHashMap<>();
        queues.put("kyc_pending", memberStats != null ? memberStats.pendingKycCount() : 0L);
        queues.put("community_reports", communityPending);
        queues.put("charge_failed", walletStats != null ? walletStats.failedChargeQueueCount() : 0L);
        queues.put("analysis_failed", docStats != null ? docStats.failedCount() : 0L);

        long dau = walletStats != null ? walletStats.dailyActiveUsers() : 0L;
        long docsAnalyzed = docStats != null ? docStats.todayAnalyzed() : 0L;
        return new DashboardSummaryResponse(totals, dau, docsAnalyzed, queues);
    }

    private AdminWalletStats safeWalletStats() {
        try {
            return walletAdminClient.stats();
        } catch (RuntimeException e) {
            log.warn("[DashboardService] walletAdminClient.stats 실패(fail-open): {}", e.getMessage());
            return null;
        }
    }

    private AdminMemberStats safeMemberStats() {
        try {
            return memberAdminClient.stats();
        } catch (RuntimeException e) {
            log.warn("[DashboardService] memberAdminClient.stats 실패(fail-open): {}", e.getMessage());
            return null;
        }
    }

    private DocumentStats safeDocStats() {
        try {
            return documentAdminClient.stats();
        } catch (RuntimeException e) {
            log.warn("[DashboardService] documentAdminClient.stats 실패(fail-open): {}", e.getMessage());
            return null;
        }
    }

    private long safeCommunityPending() {
        try {
            return communityAdminClient.pendingReportCount();
        } catch (RuntimeException e) {
            log.warn("[DashboardService] communityAdminClient.pendingReportCount 실패(fail-open): {}", e.getMessage());
            return 0L;
        }
    }

    @Override
    public DashboardAlertsResponse getAlerts() {
        // 실 데이터 기반 운영 알림. 각 client stats 에서 카운트를 가져와 0건은 노출하지 않는다
        // (정상 상태일 때 깔끔하게 비어보이도록). fail-open: 한 client 가 죽어도 다른 알림은 표시.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Alert> alerts = new java.util.ArrayList<>();

        // KYC 대기 — member stats.pending_kyc_count
        AdminMemberStats memberStats = safeMemberStats();
        if (memberStats != null && memberStats.pendingKycCount() > 0) {
            alerts.add(new Alert(
                    "KYC_PENDING", "KYC",
                    "신분증 OCR 승인 대기 " + memberStats.pendingKycCount() + "건",
                    "PENDING", now));
        }

        // 신고 게시글 — community stats.pending_report_count
        long communityPending = safeCommunityPending();
        if (communityPending > 0) {
            alerts.add(new Alert(
                    "COMMUNITY_REPORT", "커뮤니티",
                    "신고 누적 게시글 " + communityPending + "건",
                    "ACTION_NEEDED", now));
        }

        // 충전 실패 — wallet stats.failed_charge_queue_count
        AdminWalletStats walletStats = safeWalletStats();
        if (walletStats != null && walletStats.failedChargeQueueCount() > 0) {
            alerts.add(new Alert(
                    "CHARGE_FAILED", "충전",
                    "충전 실패 큐 " + walletStats.failedChargeQueueCount() + "건",
                    "REVIEW_NEEDED", now));
        }

        // AI 분석 실패 — document stats.failed_count
        DocumentStats docStats = safeDocStats();
        if (docStats != null && docStats.failedCount() > 0) {
            alerts.add(new Alert(
                    "ANALYSIS_FAILED", "AI 분석",
                    "AI 분석 실패 " + docStats.failedCount() + "건",
                    "REVIEW_NEEDED", now));
        }

        // TODO: 이상거래(SUSPICIOUS_TRANSACTION) — 부정거래 탐지 엔진(고액·고빈도·이상 IP 등) 도입 시 추가.
        //       현재는 별도 룰 엔진/메트릭이 없어 알림 미생성.

        return new DashboardAlertsResponse(alerts);
    }
}
