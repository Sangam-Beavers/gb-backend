package com.gb.admin.domain.document.service.impl;

import com.gb.admin.domain.document.dto.response.AdminDocumentPageResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentStatsResponse;
import com.gb.admin.domain.document.service.AdminDocumentService;
import com.gb.admin.global.client.AdminDocumentSummary;
import com.gb.admin.global.client.AdminMemberMini;
import com.gb.admin.global.client.DocumentAdminClient;
import com.gb.admin.global.client.MemberAdminClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDocumentServiceImpl implements AdminDocumentService {

    private final DocumentAdminClient documentAdminClient;
    private final MemberAdminClient memberAdminClient;

    @Override
    public AdminDocumentStatsResponse stats() {
        return AdminDocumentStatsResponse.from(documentAdminClient.stats());
    }

    @Override
    public AdminDocumentPageResponse recent(int page, int size) {
        Page<AdminDocumentSummary> result = documentAdminClient.recent(page, size);
        // 분석 요청자(userName)는 document-service가 모르므로 member lookup으로 보강(N+1 방지 배치, fail-open).
        List<String> ids = result.getContent().stream()
                .map(AdminDocumentSummary::userPublicId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, AdminMemberMini> members = memberAdminClient.lookup(ids);
        return AdminDocumentPageResponse.from(result.map(s -> {
            AdminMemberMini m = members.get(s.userPublicId());
            String name = m != null
                    ? (m.nickname() != null && !m.nickname().isBlank() ? m.nickname() : m.email())
                    : (s.userName() != null ? s.userName() : "Unknown");
            return AdminDocumentResponse.from(s, name);
        }));
    }
}
