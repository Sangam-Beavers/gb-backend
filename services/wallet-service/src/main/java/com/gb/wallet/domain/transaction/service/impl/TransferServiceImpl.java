package com.gb.wallet.domain.transaction.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse.AccountItem;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse.CurrencyItem;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentAccountProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.RemittanceAmountProjection;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.time.LocalDateTime;
import java.util.Arrays;
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
    private final BankAccountRepository bankAccountRepository;
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

    @Override
    public ValidateMemberResponse validateMember(String email) {
        MemberInfo member = memberClient.findByEmail(email)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        return ValidateMemberResponse.from(member);
    }

    @Override
    public SupportedCurrenciesResponse getSupportedCurrencies() {
        // CurrencyType enum이 SSOT. DB/외부 호출 없이 enum 순회로 응답 구성.
        // @Transactional이 굳이 필요 없는 순수 조회라 어노테이션을 붙이지 않는다 (클래스 레벨 readOnly도 영향 없음).
        List<CurrencyItem> items = Arrays.stream(CurrencyType.values())
                .map(CurrencyItem::from)
                .toList();
        return SupportedCurrenciesResponse.of(items);
    }

    @Override
    public RecentAccountsResponse getRecentRemittanceAccounts(String userPublicId, int size) {
        // 1) 송신자 wallet 조회. 없으면 WALLET4001.
        Wallet sender = walletRepository.findByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));

        // 2) bank_account별 최신 송금 1건씩, 최근순 N건.
        List<RecentAccountProjection> recent = transactionRepository
                .findRecentRemittanceAccounts(sender.getId(), PageRequest.of(0, size));

        if (recent.isEmpty()) {
            return RecentAccountsResponse.of(List.of());
        }

        List<Long> bankAccountIds = recent.stream()
                .map(RecentAccountProjection::getBankAccountId)
                .toList();
        List<LocalDateTime> timestamps = recent.stream()
                .map(RecentAccountProjection::getLastTransferredAt)
                .toList();

        // 3) (bankAccountId, lastTransferredAt) → amount/currency/receiverName IN-batch 1회.
        Map<Long, LocalDateTime> expectedTimestamp = recent.stream().collect(Collectors.toMap(
                RecentAccountProjection::getBankAccountId,
                RecentAccountProjection::getLastTransferredAt));
        Map<Long, RemittanceAmountProjection> lastByBankAccount = new HashMap<>();
        for (RemittanceAmountProjection row : transactionRepository
                .findAmountsForLatestRemittances(sender.getId(), bankAccountIds, timestamps)) {
            if (Objects.equals(expectedTimestamp.get(row.getBankAccountId()), row.getCreatedAt())) {
                lastByBankAccount.putIfAbsent(row.getBankAccountId(), row);
            }
        }

        // 4) BankAccount IN-batch (bank ManyToOne을 EntityGraph로 eager fetch — N+1 방지).
        Map<Long, BankAccount> accountById = bankAccountRepository.findAllByIdIn(bankAccountIds).stream()
                .collect(Collectors.toMap(BankAccount::getId, a -> a));

        // 5) projection 순서(최근순) 유지하면서 AccountItem 변환.
        List<AccountItem> items = recent.stream()
                .map(p -> {
                    BankAccount account = accountById.get(p.getBankAccountId());
                    RemittanceAmountProjection lastTx = lastByBankAccount.get(p.getBankAccountId());
                    return AccountItem.builder()
                            .bankCode(account != null ? account.getBank().getCode() : null)
                            .bankName(account != null ? account.getBank().getName() : null)
                            .accountNumber(account != null ? AccountNumberMasker.mask(account.getAccountNumber()) : null)
                            .accountHolder(lastTx != null ? lastTx.getReceiverName() : null)
                            .currencyCode(lastTx != null ? lastTx.getCurrencyCode().name() : null)
                            // 금액은 소수점 4자리 고정 string (잔액 조회 BalanceItem과 동일 규칙).
                            .lastAmount(lastTx != null ? lastTx.getAmount().setScale(4).toPlainString() : null)
                            .lastTransferredAt(RecentAccountsResponse.toUtcZ(p.getLastTransferredAt()))
                            .build();
                })
                .toList();

        return RecentAccountsResponse.of(items);
    }
}
