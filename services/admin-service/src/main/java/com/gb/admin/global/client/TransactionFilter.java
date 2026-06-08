package com.gb.admin.global.client;

import java.time.LocalDateTime;

/**
 * 거래 검색 필터. 모든 필드 nullable — 미지정 시 해당 조건 제외.
 */
public record TransactionFilter(
        LocalDateTime from,
        LocalDateTime to,
        String type,
        String status,
        String risk
) {
}
