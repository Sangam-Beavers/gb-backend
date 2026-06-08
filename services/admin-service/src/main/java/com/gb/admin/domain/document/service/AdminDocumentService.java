package com.gb.admin.domain.document.service;

import com.gb.admin.domain.document.dto.response.AdminDocumentPageResponse;
import com.gb.admin.domain.document.dto.response.AdminDocumentStatsResponse;

public interface AdminDocumentService {

    AdminDocumentStatsResponse stats();

    AdminDocumentPageResponse recent(int page, int size);
}
