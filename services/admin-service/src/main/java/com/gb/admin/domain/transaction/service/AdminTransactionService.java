package com.gb.admin.domain.transaction.service;

import com.gb.admin.domain.transaction.dto.response.AdminTransactionPageResponse;
import com.gb.admin.global.client.TransactionFilter;
import java.io.IOException;
import java.io.OutputStream;

public interface AdminTransactionService {

    AdminTransactionPageResponse search(TransactionFilter filter, int page, int size);

    /**
     * 거래 로그 CSV 스트리밍. 호출 측({@code StreamingResponseBody})이 OutputStream을 넘기면
     * UTF-8 BOM 없이 헤더 + 행을 쓴다(엑셀 호환은 후속).
     */
    void exportCsv(TransactionFilter filter, OutputStream out) throws IOException;
}
