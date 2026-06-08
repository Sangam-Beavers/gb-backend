package com.gb.admin.domain.financialAudit.service.impl;

import com.gb.admin.domain.financialAudit.dto.response.AdminChargeAttemptPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.AdminChargeAttemptResponse;
import com.gb.admin.domain.financialAudit.dto.response.FinancialAuditLogPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.FinancialAuditLogResponse;
import com.gb.admin.domain.financialAudit.dto.response.MemberLookupResponse;
import com.gb.admin.domain.financialAudit.dto.response.TransactionAuditTrailResponse;
import com.gb.admin.domain.financialAudit.service.FinancialAuditService;
import com.gb.admin.global.client.AdminAuditLogEntry;
import com.gb.admin.global.client.AdminChargeAttempt;
import com.gb.admin.global.client.AdminMemberMini;
import com.gb.admin.global.client.AdminTransactionAuditTrail;
import com.gb.admin.global.client.AuditLogFilter;
import com.gb.admin.global.client.MemberAdminClient;
import com.gb.admin.global.client.WalletAdminClient;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 금융 감사 로그 화면 BFF 서비스 — wallet 으로부터 받은 데이터에 member lookup 으로
 * 표시 정보를 enrich 한다(N+1 방지: 1회 batch 호출).
 *
 * <p>member lookup 실패 시 "Unknown" 폴백(표시용 fail-open). wallet 호출은 fail-fast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialAuditServiceImpl implements FinancialAuditService {

    private final WalletAdminClient walletAdminClient;
    private final MemberAdminClient memberAdminClient;

    @Override
    public FinancialAuditLogPageResponse searchAuditLogs(AuditLogFilter filter, int page, int size) {
        Page<AdminAuditLogEntry> entries = walletAdminClient.searchAuditLogs(filter, page, size);
        List<String> userIds = entries.getContent().stream()
                .map(AdminAuditLogEntry::userPublicId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        Map<String, AdminMemberMini> members = lookupSafe(userIds);
        Page<FinancialAuditLogResponse> mapped = entries.map(e -> FinancialAuditLogResponse.from(e, members.get(e.userPublicId())));
        return FinancialAuditLogPageResponse.from(mapped);
    }

    @Override
    public TransactionAuditTrailResponse getAuditTrail(String transactionPublicId) {
        AdminTransactionAuditTrail trail = walletAdminClient.getAuditTrail(transactionPublicId);
        List<String> userIds = new java.util.ArrayList<>();
        if (trail.transaction() != null) userIds.add(trail.transaction().userPublicId());
        if (trail.logs() != null) {
            trail.logs().forEach(l -> userIds.add(l.userPublicId()));
        }
        Map<String, AdminMemberMini> members = lookupSafe(userIds);
        return TransactionAuditTrailResponse.from(trail, members);
    }

    @Override
    public AdminChargeAttemptPageResponse searchChargeAttempts(String status, String userPublicId, int page, int size) {
        Page<AdminChargeAttempt> attempts = walletAdminClient.searchChargeAttempts(status, userPublicId, page, size);
        List<String> userIds = attempts.getContent().stream()
                .map(AdminChargeAttempt::userPublicId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        Map<String, AdminMemberMini> members = lookupSafe(userIds);
        Page<AdminChargeAttemptResponse> mapped = attempts.map(a -> AdminChargeAttemptResponse.from(a, members.get(a.userPublicId())));
        return AdminChargeAttemptPageResponse.from(mapped);
    }

    @Override
    public MemberLookupResponse lookup(Collection<String> userPublicIds) {
        Map<String, AdminMemberMini> raw = lookupSafe(userPublicIds);
        Map<String, MemberLookupResponse.MemberMiniResponse> mapped = raw.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new MemberLookupResponse.MemberMiniResponse(
                                e.getValue().userPublicId(),
                                e.getValue().email(),
                                e.getValue().nickname(),
                                e.getValue().nationality()),
                        (a, b) -> a,
                        LinkedHashMap::new));
        return new MemberLookupResponse(mapped);
    }

    /** member lookup 안전판. Real 빈 실패 시 빈 맵 반환 — 호출 측이 "Unknown" 폴백. */
    private Map<String, AdminMemberMini> lookupSafe(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        try {
            return memberAdminClient.lookup(userIds);
        } catch (RuntimeException e) {
            log.warn("[FinancialAuditService] member lookup 실패(fail-open): {}", e.getMessage());
            return Map.of();
        }
    }
}
