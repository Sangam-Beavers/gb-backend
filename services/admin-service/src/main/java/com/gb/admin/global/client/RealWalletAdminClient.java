package com.gb.admin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.admin.global.config.InternalApiProperties;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * wallet-service /api/v1/internal/admin/* 를 호출하는 실 클라이언트.
 *
 * <p>금액은 wallet 응답에서 String 으로 오므로 BigDecimal 로 파싱한다.
 * 사용자 표시 정보(userName)는 본 클라이언트가 갖고 있지 않으니 null 로 두고
 * admin-service 쪽에서 MemberAdminClient.lookup 으로 enrich 한다.
 */
@Slf4j
@Component
@Profile("!mock-clients")
public class RealWalletAdminClient implements WalletAdminClient {

    private final RestClient restClient;
    private final String baseUrl;

    public RealWalletAdminClient(RestClient internalApiRestClient, InternalApiProperties props) {
        this.restClient = internalApiRestClient;
        Map<String, String> urls = props.urls();
        this.baseUrl = urls == null ? null : urls.get("wallet");
        if (this.baseUrl == null || this.baseUrl.isBlank()) {
            throw new IllegalStateException("admin.internal-api.urls.wallet 가 비어 있습니다 — 환경변수 WALLET_INTERNAL_URL 확인.");
        }
    }

    @Override
    public Page<AdminTransactionSummary> searchTransactions(TransactionFilter filter, int page, int size) {
        return searchTransactionsForUser(filter, null, page, size);
    }

    @Override
    public Page<AdminTransactionSummary> searchTransactionsForUser(TransactionFilter filter,
                                                                    String userPublicId,
                                                                    int page, int size) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/admin/transactions")
                    .queryParam("page", page)
                    .queryParam("size", size);
            if (filter != null) {
                if (filter.from() != null) b.queryParam("from", isoFormat(filter.from()));
                if (filter.to() != null) b.queryParam("to", isoFormat(filter.to()));
                if (filter.type() != null) b.queryParam("type", filter.type());
                if (filter.status() != null) b.queryParam("status", filter.status());
                if (filter.risk() != null) b.queryParam("risk", filter.risk());
            }
            if (userPublicId != null && !userPublicId.isBlank()) {
                b.queryParam("user_public_id", userPublicId);
            }
            InternalApiEnvelope<TransactionPagePayload> env = restClient.get().uri(b.build().toUri())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<TransactionPagePayload>>() {});
            TransactionPagePayload p = env == null ? null : env.data();
            if (p == null || p.transactions() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminTransactionSummary> mapped = p.transactions().stream()
                    .map(RealWalletAdminClient::toSummary)
                    .collect(Collectors.toList());
            return new PageImpl<>(mapped, PageRequest.of(p.page(), Math.max(p.size(), 1)), p.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealWalletAdminClient] searchTransactions 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public AdminTransactionAuditTrail getAuditTrail(String transactionPublicId) {
        try {
            InternalApiEnvelope<AuditTrailPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/transactions/{id}/audit-logs", transactionPublicId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<AuditTrailPayload>>() {});
            if (env == null || env.data() == null || env.data().transaction() == null) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            AuditTrailPayload p = env.data();
            return new AdminTransactionAuditTrail(
                    toSummary(p.transaction()),
                    p.logs() == null ? List.of()
                            : p.logs().stream().map(RealWalletAdminClient::toAuditLogEntry).toList());
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealWalletAdminClient] getAuditTrail 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Page<AdminAuditLogEntry> searchAuditLogs(AuditLogFilter filter, int page, int size) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/admin/transaction-audit-logs")
                    .queryParam("page", page).queryParam("size", size);
            if (filter != null) {
                if (filter.userPublicId() != null) b.queryParam("user_public_id", filter.userPublicId());
                if (filter.action() != null) b.queryParam("action", filter.action());
                if (filter.status() != null) b.queryParam("status", filter.status());
                if (filter.from() != null) b.queryParam("from", isoFormat(filter.from()));
                if (filter.to() != null) b.queryParam("to", isoFormat(filter.to()));
                if (filter.minAmount() != null) b.queryParam("min_amount", filter.minAmount().toPlainString());
                if (filter.maxAmount() != null) b.queryParam("max_amount", filter.maxAmount().toPlainString());
                if (filter.ipAddress() != null) b.queryParam("ip_address", filter.ipAddress());
            }
            InternalApiEnvelope<AuditLogPagePayload> env = restClient.get().uri(b.build().toUri())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<AuditLogPagePayload>>() {});
            AuditLogPagePayload p = env == null ? null : env.data();
            if (p == null || p.logs() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminAuditLogEntry> mapped = p.logs().stream().map(RealWalletAdminClient::toAuditLogEntry).toList();
            return new PageImpl<>(mapped, PageRequest.of(p.page(), Math.max(p.size(), 1)), p.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealWalletAdminClient] searchAuditLogs 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Page<AdminChargeAttempt> searchChargeAttempts(String status, String userPublicId, int page, int size) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/admin/charge-attempts")
                    .queryParam("page", page).queryParam("size", size);
            if (status != null) b.queryParam("status", status);
            if (userPublicId != null) b.queryParam("user_public_id", userPublicId);
            InternalApiEnvelope<ChargeAttemptPagePayload> env = restClient.get().uri(b.build().toUri())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<ChargeAttemptPagePayload>>() {});
            ChargeAttemptPagePayload p = env == null ? null : env.data();
            if (p == null || p.attempts() == null) {
                return new PageImpl<>(List.of(), PageRequest.of(page, Math.max(size, 1)), 0);
            }
            List<AdminChargeAttempt> mapped = p.attempts().stream()
                    .map(w -> new AdminChargeAttempt(
                            w.chargeAttemptPublicId(),
                            w.userPublicId(),
                            parseAmount(w.amount()),
                            w.currencyCode(),
                            w.status(),
                            w.reason(),
                            w.bankAccountId(),
                            w.createdAt()))
                    .toList();
            return new PageImpl<>(mapped, PageRequest.of(p.page(), Math.max(p.size(), 1)), p.totalElements());
        } catch (RuntimeException e) {
            log.error("[RealWalletAdminClient] searchChargeAttempts 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public AdminWalletStats stats() {
        try {
            InternalApiEnvelope<WalletStatsPayload> env = restClient.get()
                    .uri(baseUrl + "/api/v1/internal/admin/stats/transactions")
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalApiEnvelope<WalletStatsPayload>>() {});
            if (env == null || env.data() == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            WalletStatsPayload p = env.data();
            Map<String, BigDecimal> totals = new java.util.LinkedHashMap<>();
            if (p.todayTransactionsTotal() != null) {
                p.todayTransactionsTotal().forEach((k, v) -> totals.put(k, parseAmount(v)));
            }
            return new AdminWalletStats(
                    totals,
                    p.byAction() == null ? Map.of() : p.byAction(),
                    p.byStatus() == null ? Map.of() : p.byStatus(),
                    p.remittanceSuccessRate(),
                    p.chargeSuccessRate(),
                    p.dailyActiveUsers(),
                    p.failedChargeQueueCount());
        } catch (BusinessException be) {
            throw be;
        } catch (RuntimeException e) {
            log.error("[RealWalletAdminClient] stats 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static AdminTransactionSummary toSummary(TransactionWire t) {
        return new AdminTransactionSummary(
                t.transactionPublicId(),
                t.userPublicId(),
                null, // userName 은 admin-service 에서 lookup 으로 enrich
                t.type(),
                parseAmount(t.amount()),
                t.currencyCode(),
                t.status(),
                t.riskLevel(),
                t.executedAt());
    }

    private static AdminAuditLogEntry toAuditLogEntry(AuditLogWire l) {
        return new AdminAuditLogEntry(
                l.auditLogPublicId(),
                l.transactionPublicId(),
                l.userPublicId(),
                l.action(),
                parseAmount(l.amount()),
                l.currencyCode(),
                parseAmount(l.beforeBalance()),
                parseAmount(l.afterBalance()),
                l.status(),
                l.reason(),
                l.ipAddress(),
                l.createdAt());
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String isoFormat(LocalDateTime t) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t);
    }

    // ===== wire DTO =====
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionPagePayload(
            @JsonProperty("transactions") List<TransactionWire> transactions,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionWire(
            @JsonProperty("transaction_public_id") String transactionPublicId,
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("type") String type,
            @JsonProperty("amount") String amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("status") String status,
            @JsonProperty("risk_level") String riskLevel,
            @JsonProperty("executed_at") LocalDateTime executedAt,
            @JsonProperty("receiver_user_public_id") String receiverUserPublicId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuditTrailPayload(
            @JsonProperty("transaction") TransactionWire transaction,
            @JsonProperty("logs") List<AuditLogWire> logs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuditLogPagePayload(
            @JsonProperty("logs") List<AuditLogWire> logs,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuditLogWire(
            @JsonProperty("audit_log_public_id") String auditLogPublicId,
            @JsonProperty("transaction_public_id") String transactionPublicId,
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("action") String action,
            @JsonProperty("amount") String amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("before_balance") String beforeBalance,
            @JsonProperty("after_balance") String afterBalance,
            @JsonProperty("status") String status,
            @JsonProperty("reason") String reason,
            @JsonProperty("ip_address") String ipAddress,
            @JsonProperty("created_at") LocalDateTime createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeAttemptPagePayload(
            @JsonProperty("attempts") List<ChargeAttemptWire> attempts,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeAttemptWire(
            @JsonProperty("charge_attempt_public_id") String chargeAttemptPublicId,
            @JsonProperty("user_public_id") String userPublicId,
            @JsonProperty("amount") String amount,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("status") String status,
            @JsonProperty("reason") String reason,
            @JsonProperty("bank_account_id") Long bankAccountId,
            @JsonProperty("created_at") LocalDateTime createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalletStatsPayload(
            @JsonProperty("today_transactions_total") Map<String, String> todayTransactionsTotal,
            @JsonProperty("by_action") Map<String, Long> byAction,
            @JsonProperty("by_status") Map<String, Long> byStatus,
            @JsonProperty("remittance_success_rate") String remittanceSuccessRate,
            @JsonProperty("charge_success_rate") String chargeSuccessRate,
            @JsonProperty("daily_active_users") long dailyActiveUsers,
            @JsonProperty("failed_charge_queue_count") long failedChargeQueueCount) {
    }
}
