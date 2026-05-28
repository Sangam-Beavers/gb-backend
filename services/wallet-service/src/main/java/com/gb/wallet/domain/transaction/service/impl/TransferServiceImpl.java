package com.gb.wallet.domain.transaction.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferServiceImpl implements TransferService {

    /** 명세상 고정 10명. 페이지네이션 없음. */
    private static final int RECENT_LIMIT = 10;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MemberClient memberClient;

    @Override
    public RecentRecipientsResponse getRecentInternalRecipients(String userPublicId) {
        // 1) 송신자 wallet 조회. 없으면 WALLET4001.
        Wallet sender = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // 2) 수신자별 최신 송금 1건씩, 최근순 10건.
        List<RecentRecipientProjection> recent = transactionRepository
                .findRecentInternalTransferRecipients(sender.getId(), PageRequest.of(0, RECENT_LIMIT));

        if (recent.isEmpty()) {
            return RecentRecipientsResponse.of(List.of());
        }

        List<Long> receiverIds = recent.stream()
                .map(RecentRecipientProjection::getReceiverWalletId)
                .toList();
        List<LocalDateTime> timestamps = recent.stream()
                .map(RecentRecipientProjection::getLastTransferredAt)
                .toList();

        // 3) (receiverWalletId, lastTransferredAt) → currency_code IN-batch 1회.
        //    서로 다른 receiver가 같은 timestamp를 갖는 희박한 충돌은 정확 매칭으로 한 번 더 검증.
        Map<Long, LocalDateTime> expectedTimestamp = recent.stream().collect(Collectors.toMap(
                RecentRecipientProjection::getReceiverWalletId,
                RecentRecipientProjection::getLastTransferredAt));
        Map<Long, CurrencyType> currencyByReceiver = new HashMap<>();
        for (ReceiverCurrencyProjection row : transactionRepository
                .findCurrencyCodesForLatestTransfers(sender.getId(), receiverIds, timestamps)) {
            if (Objects.equals(expectedTimestamp.get(row.getReceiverWalletId()), row.getCreatedAt())) {
                currencyByReceiver.putIfAbsent(row.getReceiverWalletId(), row.getCurrencyCode());
            }
        }

        // 4) receiverWalletId → user_public_id 매핑. JpaRepository 내장 findAllById(IN-batch) 사용.
        Map<Long, String> userPublicIdByWallet = walletRepository.findAllById(receiverIds).stream()
                .collect(Collectors.toMap(Wallet::getId, Wallet::getUserPublicId));

        // 5) 각 수신자에 대해 MemberClient 호출 → RecipientItem 변환. projection 순서(최근순) 유지.
        // TODO: member-service 도입 시 N번 호출은 batch API(예: GET /members?ids=...)로 최적화.
        List<RecipientItem> items = recent.stream()
                .map(p -> {
                    String receiverUserId = userPublicIdByWallet.get(p.getReceiverWalletId());
                    MemberInfo member = memberClient.getMember(receiverUserId);
                    CurrencyType lastCurrency = currencyByReceiver.get(p.getReceiverWalletId());
                    return RecipientItem.builder()
                            .memberPublicId(receiverUserId)
                            .nickname(member.nickname())
                            .nationality(member.nationality())
                            .isVerified(member.isVerified())
                            .temperatureGrade(member.temperatureGrade())
                            .lastCurrencyCode(lastCurrency != null ? lastCurrency.name() : null)
                            .lastTransferredAt(RecentRecipientsResponse.toUtcZ(p.getLastTransferredAt()))
                            .build();
                })
                .toList();

        return RecentRecipientsResponse.of(items);
    }
}
