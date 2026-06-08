package com.gb.admin.domain.transaction.service.impl;

import com.gb.admin.domain.transaction.dto.response.AdminTransactionPageResponse;
import com.gb.admin.domain.transaction.dto.response.AdminTransactionResponse;
import com.gb.admin.domain.transaction.service.AdminTransactionService;
import com.gb.admin.global.client.AdminTransactionSummary;
import com.gb.admin.global.client.TransactionFilter;
import com.gb.admin.global.client.WalletAdminClient;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTransactionServiceImpl implements AdminTransactionService {

    /** CSV 1회 export 상한(Phase 1) — 더 큰 데이터는 chunk export로 후속 처리. */
    private static final int CSV_PAGE_SIZE = 1000;

    private final WalletAdminClient walletAdminClient;

    @Override
    public AdminTransactionPageResponse search(TransactionFilter filter, int page, int size) {
        Page<AdminTransactionSummary> result = walletAdminClient.searchTransactions(filter, page, size);
        return AdminTransactionPageResponse.from(result.map(AdminTransactionResponse::from));
    }

    @Override
    public void exportCsv(TransactionFilter filter, OutputStream out) throws IOException {
        // CSV는 RFC 4180 최소 준수 — 쉼표/따옴표/개행 포함 시 따옴표 escape. 라이브러리 없이 자체 작성.
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write("transaction_public_id,user_public_id,user_name,type,amount,currency_code,"
                + "status,risk_level,executed_at\n");
        Page<AdminTransactionSummary> page = walletAdminClient.searchTransactions(filter, 0, CSV_PAGE_SIZE);
        for (AdminTransactionSummary s : page.getContent()) {
            writer.write(csv(s.transactionPublicId()));
            writer.write(',');
            writer.write(csv(s.userPublicId()));
            writer.write(',');
            writer.write(csv(s.userName()));
            writer.write(',');
            writer.write(csv(s.type()));
            writer.write(',');
            writer.write(csv(s.amount() == null ? "" : s.amount().toPlainString()));
            writer.write(',');
            writer.write(csv(s.currencyCode()));
            writer.write(',');
            writer.write(csv(s.status()));
            writer.write(',');
            writer.write(csv(s.riskLevel()));
            writer.write(',');
            writer.write(csv(s.executedAt() == null ? ""
                    : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(s.executedAt()) + "Z"));
            writer.write('\n');
        }
        writer.flush();
    }

    /** RFC 4180 escape — 쉼표/따옴표/개행 있으면 따옴표 둘러싸고 내부 따옴표는 두 번. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
