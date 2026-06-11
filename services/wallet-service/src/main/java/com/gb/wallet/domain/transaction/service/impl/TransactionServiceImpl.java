package com.gb.wallet.domain.transaction.service.impl;

import com.gb.wallet.domain.transaction.dto.response.TransactionHistoryItemResponse;
import com.gb.wallet.domain.transaction.dto.response.TransactionListResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.TransactionService;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final MemberClient memberClient;

    @Override
    public TransactionListResponse getMyTransactions(String userPublicId, int page, int size) {
        // 본인 전 유형 거래를 최근순으로 페이지 조회. 잔액이 아니라 거래 이력이라 캐시하지 않는다.
        // 지갑 미존재/거래 없음은 예외가 아니라 빈 페이지로 반환한다(getExchanges와 동일 정책).
        // INTERNAL_TRANSFER 수신자 거래까지 포함하기 위해 송수신 OR 조회를 사용하고,
        // 본인 기준 OUT/IN(direction)은 매핑 단계에서 결정한다.
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> result = transactionRepository.findByMineSendingOrReceiving(userPublicId, pageable);

        // 앱 사용자 간 송금(INTERNAL_TRANSFER)의 거래 상대 닉네임을 표시한다(이슈 #207). 상대 user_public_id를
        // 페이지 단위로 모아 MemberClient.getMembers로 배치 1회 조회(건별 HTTP N+1 회피 — recent-recipients 패턴).
        // getMembers는 요청한 모든 id를 키로 포함(누락·장애=fallback)하므로 표시 실패가 API 실패로 전파되지 않는다(fail-open).
        List<String> counterpartyIds = result.getContent().stream()
                .map(tx -> TransactionHistoryItemResponse.counterpartyUserPublicId(tx, userPublicId))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, MemberInfo> membersById = counterpartyIds.isEmpty()
                ? Map.of()
                : memberClient.getMembers(counterpartyIds);

        List<TransactionHistoryItemResponse> items = result.getContent().stream()
                .map(tx -> TransactionHistoryItemResponse.from(tx, userPublicId, membersById))
                .toList();
        return TransactionListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
