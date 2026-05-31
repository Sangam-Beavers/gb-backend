package com.gb.wallet.domain.transaction.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse.AccountItem;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
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
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    // 송금 수수료 정책 상수.
    // TODO: 수수료 정책 확정 시 정책 테이블/외부 조회로 교체. 현재 0.5%는 임시 값
    //       (docs/remittance/api-spec.md §4 참고). 정책 SSOT가 docs라 코드 상수 동기화 주의.
    private static final BigDecimal REMITTANCE_FEE_RATE = new BigDecimal("0.005");
    private static final int FEE_SCALE = 4;

    /** 수수료 API가 허용하는 송금 유형. TransactionType 중 CHARGE/EXCHANGE는 거부. */
    private static final Set<TransactionType> ALLOWED_TRANSFER_TYPES =
            EnumSet.of(TransactionType.INTERNAL_TRANSFER, TransactionType.REMITTANCE);

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final BankAccountRepository bankAccountRepository;
    private final MemberClient memberClient;
    private final BankClient bankClient;

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

    @Override
    public AccountHolderResponse getAccountHolder(String bankCode, String accountNumber) {
        // DB 안 보고 외부 Mock 은행만 호출. 외부 에러는 BankErrorMapper가 BusinessException으로 변환해
        // 던지므로 (BANK4040→ACCOUNT4001, 네트워크 실패→COMMON5031 등) Service에서 try-catch 불필요.
        return AccountHolderResponse.from(bankClient.inquiry(bankCode, accountNumber));
    }

    @Override
    public TransferFeeResponse getTransferFee(TransferFeeRequest request) {
        // 1) 통화 도메인 검증 — KRW/USD/PHP/VND 외는 TRANSFER4002. (형식 검증은 @Valid 단계에서 끝남)
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));

        // 2) 송금 유형 도메인 검증 — INTERNAL_TRANSFER/REMITTANCE 외는 TRANSFER4003.
        //    .filter로 CHARGE/EXCHANGE(다른 도메인 값)도 거부. currency 검증과 동일한 Optional 패턴.
        TransactionType transferType = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));

        // 3) amount 파싱. @Pattern으로 형식 보장됨(양수 십진수, 소수 4자리 이내).
        BigDecimal amount = new BigDecimal(request.amount());

        // 4) 송금 방식별 수수료. ALLOWED_TRANSFER_TYPES 필터로 두 값만 통과돼 default는 실제로 도달 불가.
        BigDecimal fee = switch (transferType) {
            case INTERNAL_TRANSFER -> BigDecimal.ZERO.setScale(FEE_SCALE, RoundingMode.HALF_UP);
            case REMITTANCE -> amount.multiply(REMITTANCE_FEE_RATE)
                    .setScale(FEE_SCALE, RoundingMode.HALF_UP);
            // ALLOWED_TRANSFER_TYPES가 막아주지만 enum 전체 case를 망라하는 안전망 — 도달 시 도메인 에러로 일관 처리.
            default -> throw new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
        };

        // 5) total = amount + fee. add는 scale = max(scale)을 따르므로 명시 setScale로 4자리 고정.
        BigDecimal totalDeduct = amount.add(fee).setScale(FEE_SCALE, RoundingMode.HALF_UP);

        return TransferFeeResponse.of(fee, currency, totalDeduct);
    }
}
