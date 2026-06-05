package com.gb.wallet.domain.transaction.service.impl;

import com.gb.wallet.domain.transaction.dto.response.TransactionHistoryItemResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.TransactionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;

    @Override
    public TransactionListResponse getMyTransactions(String userPublicId, int page, int size) {
        // 본인 전 유형 거래를 최근순으로 페이지 조회. 잔액이 아니라 거래 이력이라 캐시하지 않는다.
        // 지갑 미존재/거래 없음은 예외가 아니라 빈 페이지로 반환한다(getExchanges와 동일 정책).
        // INTERNAL_TRANSFER 수신자 거래까지 포함하기 위해 송수신 OR 조회를 사용하고,
        // 본인 기준 OUT/IN(direction)은 매핑 단계에서 결정한다.
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> result = transactionRepository.findByMineSendingOrReceiving(userPublicId, pageable);
        List<TransactionHistoryItemResponse> items = result.getContent().stream()
                .map(tx -> TransactionHistoryItemResponse.from(tx, userPublicId))
                .toList();
        return TransactionListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
