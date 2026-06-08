package com.gb.admin.global.client;

import org.springframework.data.domain.Page;

public interface DocumentAdminClient {

    DocumentStats stats();

    Page<AdminDocumentSummary> recent(int page, int size);
}
