package com.gb.wallet.domain.admin.service;

import com.gb.wallet.domain.admin.dto.response.AdminTransactionPageResponse;
import com.gb.wallet.domain.admin.dto.response.ChargeAttemptPageResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditLogPageResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditTrailResponse;
import com.gb.wallet.domain.admin.dto.response.RevenueStatsResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionStatsResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관리자 BFF 전용 내부 API의 도메인 서비스 계약.
 * /api/v1/internal/admin/* 컨트롤러가 호출한다.
 */
public interface WalletAdminInternalService {

    AdminTransactionPageResponse searchTransactions(
            LocalDateTime from, LocalDateTime to,
            String type, String status, String risk,
            String userPublicId, int page, int size);

    TransactionAuditTrailResponse getAuditTrail(String transactionPublicId);

    TransactionAuditLogPageResponse searchAuditLogs(
            String userPublicId, String action, String status,
            LocalDateTime from, LocalDateTime to,
            BigDecimal minAmount, BigDecimal maxAmount,
            String ipAddress, int page, int size);

    ChargeAttemptPageResponse searchChargeAttempts(
            String status, String userPublicId, int page, int size);

    TransactionStatsResponse getStats(LocalDateTime from, LocalDateTime to);

    /** 수익(환전/송금 수수료) 통계 — 누적·이번 달·통화별·월별 추이. */
    RevenueStatsResponse getRevenue();
}
