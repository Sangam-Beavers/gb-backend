package com.gb.admin.domain.document.service.impl;

import com.gb.admin.domain.document.dto.response.AdminDocumentPageResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentStatsResponse;
import com.gb.admin.domain.document.service.AdminDocumentService;
import com.gb.admin.global.client.AdminDocumentSummary;
import com.gb.admin.global.client.DocumentAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDocumentServiceImpl implements AdminDocumentService {

    private final DocumentAdminClient documentAdminClient;

    @Override
    public AdminDocumentStatsResponse stats() {
        return AdminDocumentStatsResponse.from(documentAdminClient.stats());
    }

    @Override
    public AdminDocumentPageResponse recent(int page, int size) {
        Page<AdminDocumentSummary> result = documentAdminClient.recent(page, size);
        return AdminDocumentPageResponse.from(result.map(AdminDocumentResponse::from));
    }
}
