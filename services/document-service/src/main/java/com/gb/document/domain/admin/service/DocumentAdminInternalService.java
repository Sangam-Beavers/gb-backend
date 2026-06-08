package com.gb.document.domain.admin.service;

import com.gb.document.domain.admin.dto.response.AdminDocumentPageResponse;
import com.gb.document.domain.admin.dto.response.DocumentStatsResponse;
import java.time.LocalDateTime;

public interface DocumentAdminInternalService {

    AdminDocumentPageResponse search(String userPublicId, String riskLevel, int page, int size);

    DocumentStatsResponse stats(LocalDateTime from, LocalDateTime to);
}
