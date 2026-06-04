package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;

public interface TransactionService {

    /**
     * 회원(user_public_id)의 모든 유형 거래내역을 최근순으로 페이지 조회한다
     * ({@code GET /api/v1/wallets/me/transactions}). 잔액이 아니라 거래 이력이라 캐시하지 않고 DB에서 직접
     * 조회한다(CLAUDE.md §8). 지갑이 없거나 거래가 없으면 빈 페이지(빈 배열 + 메타)를 반환한다 — 예외 없음.
     */
    TransactionListResponse getMyTransactions(String userPublicId, int page, int size);
}
