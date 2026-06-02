package com.gb.wallet.domain.exchange.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 환전 내역 목록 응답 (명세 §10). {@code exchanges} 배열 + 페이지 메타(page/size/total_elements/total_pages).
 * 필드명은 전역 SNAKE_CASE 설정에 위임한다({@code totalElements} → {@code total_elements}). 게시글 목록
 * ({@code PostListResponse})과 동일한 페이지 응답 구조.
 */
@Getter
public class ExchangeListResponse {

    @Schema(description = "환전 내역 목록(최근순)")
    private final List<ExchangeResponse> exchanges;

    @Schema(description = "현재 페이지(0부터)", example = "0")
    private final int page;

    @Schema(description = "페이지당 건수", example = "20")
    private final int size;

    @Schema(description = "전체 건수", example = "42")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "3")
    private final int totalPages;

    private ExchangeListResponse(List<ExchangeResponse> exchanges, int page, int size,
                                long totalElements, int totalPages) {
        this.exchanges = exchanges;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public static ExchangeListResponse of(List<ExchangeResponse> exchanges, int page, int size,
                                          long totalElements, int totalPages) {
        return new ExchangeListResponse(exchanges, page, size, totalElements, totalPages);
    }
}