package com.gb.wallet.domain.transaction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 전자지갑 거래내역 목록 응답(명세 §3). {@code transactions} 배열 + 페이지 메타(page/size/total_elements/
 * total_pages). 필드명은 전역 SNAKE_CASE 설정에 위임한다({@code totalElements} → {@code total_elements}).
 * 환전 내역 목록({@code ExchangeListResponse})과 동일한 페이지 응답 구조.
 */
@Getter
public class TransactionListResponse {

    @Schema(description = "거래내역 목록(최근순)")
    private final List<TransactionHistoryItemResponse> transactions;

    @Schema(description = "현재 페이지(0부터)", example = "0")
    private final int page;

    @Schema(description = "페이지당 건수", example = "20")
    private final int size;

    @Schema(description = "전체 건수", example = "42")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "3")
    private final int totalPages;

    private TransactionListResponse(List<TransactionHistoryItemResponse> transactions, int page, int size,
                                    long totalElements, int totalPages) {
        this.transactions = transactions;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public static TransactionListResponse of(List<TransactionHistoryItemResponse> transactions, int page, int size,
                                             long totalElements, int totalPages) {
        return new TransactionListResponse(transactions, page, size, totalElements, totalPages);
    }
}
