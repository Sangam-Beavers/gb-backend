package com.gb.admin.domain.financialAudit.service;

import com.gb.admin.domain.financialAudit.dto.response.AdminChargeAttemptPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.FinancialAuditLogPageResponse;
import com.gb.admin.domain.financialAudit.dto.response.MemberLookupResponse;
import com.gb.admin.domain.financialAudit.dto.response.TransactionAuditTrailResponse;
import com.gb.admin.global.client.AuditLogFilter;
import java.util.Collection;

public interface FinancialAuditService {

    FinancialAuditLogPageResponse searchAuditLogs(AuditLogFilter filter, int page, int size);

    TransactionAuditTrailResponse getAuditTrail(String transactionPublicId);

    AdminChargeAttemptPageResponse searchChargeAttempts(String status, String userPublicId, int page, int size);

    MemberLookupResponse lookup(Collection<String> userPublicIds);
}
