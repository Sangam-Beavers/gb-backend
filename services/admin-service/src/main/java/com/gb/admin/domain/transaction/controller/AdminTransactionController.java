package com.gb.admin.domain.transaction.controller;

import com.gb.admin.domain.transaction.dto.response.AdminTransactionPageResponse;
import com.gb.admin.domain.transaction.service.AdminTransactionService;
import com.gb.admin.global.client.TransactionFilter;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Admin Transactions", description = "관리자 거래 로그 조회/내보내기")
@RestController
@RequestMapping("/api/v1/admin/transactions")
@RequiredArgsConstructor
public class AdminTransactionController {

    private final AdminTransactionService adminTransactionService;

    @Operation(summary = "거래 로그 검색",
            description = "타입/상태/리스크/시각 범위로 필터링. wallet-service /internal/admin은 다음 스프린트 — Phase 1은 Mock fixture.")
    @GetMapping
    public ApiResponse<AdminTransactionPageResponse> search(
            @Parameter(description = "시작 시각(UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "종료 시각(UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "거래 유형(INTERNAL_TRANSFER 등)") @RequestParam(required = false) String type,
            @Parameter(description = "거래 상태(COMPLETED 등)") @RequestParam(required = false) String status,
            @Parameter(description = "리스크(LOW/MEDIUM/HIGH)") @RequestParam(required = false) String risk,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TransactionFilter filter = new TransactionFilter(from, to, type, status, risk);
        return ApiResponse.success(adminTransactionService.search(filter, page, size));
    }

    @Operation(summary = "거래 로그 CSV 내보내기",
            description = "동일 필터 조건의 거래를 CSV(UTF-8)로 스트리밍. Content-Disposition: attachment.")
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String risk) {
        TransactionFilter filter = new TransactionFilter(from, to, type, status, risk);
        String filename = "transactions-" + LocalDate.now() + ".csv";
        StreamingResponseBody body = out -> adminTransactionService.exportCsv(filter, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
