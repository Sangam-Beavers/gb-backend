package com.gb.wallet.domain.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.request.ValidateScheduledRequest;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse.AccountItem;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse.CurrencyItem;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentAccountProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.RemittanceAmountProjection;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.impl.TransferServiceImpl;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TransferServiceImpl}의 순수 단위 테스트.
 * Spring 컨텍스트/DB 없이 Mockito로만. Repository/MemberClient 응답을 stub해 매핑 로직만 검증한다.
 *
 * <p>직렬화(snake_case, JSON)는 단위 테스트 범위 밖이라 응답 객체의 필드값까지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private MemberClient memberClient;
    @Mock private BankClient bankClient;
    @InjectMocks private TransferServiceImpl transferService;

    private static final String SENDER_PUBLIC_ID = "sender-uuid";
    private static final LocalDateTime T_LINH  = LocalDateTime.of(2026, 5, 25, 10, 0);
    private static final LocalDateTime T_MARIA = LocalDateTime.of(2026, 5, 22, 10, 0);

    @Test
    @DisplayName("정상: 수신자별 최신 송금이 최근순으로 매핑되고 통화·회원 정보가 결합된다")
    void getRecentInternalRecipients_정상_조합() {
        Wallet sender      = wallet(1L,  SENDER_PUBLIC_ID);
        Wallet linhWallet  = wallet(10L, "linh-uuid");
        Wallet mariaWallet = wallet(20L, "maria-uuid");

        given(walletRepository.findByUserPublicId(SENDER_PUBLIC_ID))
                .willReturn(Optional.of(sender));

        // Repository는 이미 lastTransferredAt DESC 정렬된 결과를 돌려준다고 가정 (Repository 테스트에서 검증).
        given(transactionRepository.findRecentInternalTransferRecipients(eq(1L), any(Pageable.class)))
                .willReturn(List.of(
                        recentRecipient(linhWallet.getId(),  T_LINH),
                        recentRecipient(mariaWallet.getId(), T_MARIA)));

        given(transactionRepository.findCurrencyCodesForLatestTransfers(
                eq(1L),
                eq(List.of(linhWallet.getId(), mariaWallet.getId())),
                eq(List.of(T_LINH, T_MARIA))))
                .willReturn(List.of(
                        receiverCurrency(linhWallet.getId(),  CurrencyType.KRW, T_LINH),
                        receiverCurrency(mariaWallet.getId(), CurrencyType.VND, T_MARIA)));

        given(walletRepository.findAllById(List.of(linhWallet.getId(), mariaWallet.getId())))
                .willReturn(List.of(linhWallet, mariaWallet));

        given(memberClient.getMembers(List.of("linh-uuid", "maria-uuid")))
                .willReturn(Map.of(
                        "linh-uuid", new MemberInfo("linh-uuid", "linh-test@example.com",
                                "Nguyen Thi Linh", "Linh", "VN", true),
                        "maria-uuid", new MemberInfo("maria-uuid", "maria-test@example.com",
                                "Maria Santos", "Maria", "PH", true)));

        RecentRecipientsResponse response =
                transferService.getRecentInternalRecipients(SENDER_PUBLIC_ID);

        // N+1 회귀 가드: 수신자 표시 정보는 배치 1회(getMembers)로만 — 건별 getMember 호출 금지.
        verify(memberClient, times(1)).getMembers(List.of("linh-uuid", "maria-uuid"));
        verify(memberClient, never()).getMember(anyString());

        assertThat(response.getReceivers())
                .as("순서(Linh→Maria) + 모든 필드 매핑 검증. lastTransferredAt은 ISO 8601 UTC Z 문자열")
                .extracting(RecipientItem::getMemberPublicId,
                            RecipientItem::getNickname,
                            RecipientItem::getNationality,
                            RecipientItem::isVerified,
                            RecipientItem::getLastCurrencyCode,
                            RecipientItem::getLastTransferredAt)
                .containsExactly(
                        tuple("linh-uuid",  "Linh",  "VN", true, "KRW", "2026-05-25T10:00:00Z"),
                        tuple("maria-uuid", "Maria", "PH", true, "VND", "2026-05-22T10:00:00Z"));
    }

    @Test
    @DisplayName("지갑 없음: WALLET_NOT_FOUND BusinessException, 이후 TransactionRepository·MemberClient 호출 없음")
    void getRecentInternalRecipients_지갑_없음() {
        given(walletRepository.findByUserPublicId("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getRecentInternalRecipients("unknown"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(transactionRepository, memberClient);
    }

    @Test
    @DisplayName("송금 이력 없음: receivers 빈 배열 반환, MemberClient 호출 없음")
    void getRecentInternalRecipients_빈_결과() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);

        given(walletRepository.findByUserPublicId(SENDER_PUBLIC_ID))
                .willReturn(Optional.of(sender));
        given(transactionRepository.findRecentInternalTransferRecipients(eq(1L), any(Pageable.class)))
                .willReturn(List.of());

        RecentRecipientsResponse response =
                transferService.getRecentInternalRecipients(SENDER_PUBLIC_ID);

        assertThat(response.getReceivers()).isEmpty();
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("validateMember 정상: 이메일로 찾은 회원의 publicId/nickname/isVerified가 응답에 매핑된다")
    void validateMember_정상() {
        String email = "linh@example.com";
        MemberInfo linh = new MemberInfo(
                "11111111-1111-1111-1111-111111111111",
                email,
                "Nguyen Thi Linh",
                "Linh",
                "VN",
                true);
        given(memberClient.findByEmail(email)).willReturn(Optional.of(linh));

        ValidateMemberResponse response = transferService.validateMember(email);

        assertThat(response.getReceiverPublicId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(response.getNickname()).isEqualTo("Linh");
        assertThat(response.isVerified()).isTrue();

        // wallet DB 미접근 검증: wallet/transaction Repository는 호출되면 안 된다.
        verifyNoInteractions(walletRepository, transactionRepository);
    }

    @Test
    @DisplayName("validateMember 없는 회원: MEMBER_NOT_FOUND BusinessException, wallet DB 미접근")
    void validateMember_없는_회원() {
        String email = "nobody@example.com";
        given(memberClient.findByEmail(email)).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.validateMember(email))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(walletRepository, transactionRepository);
    }

    @Test
    @DisplayName("getSupportedCurrencies: 4종 통화 모두 enum 정의대로 반환, DB/외부 호출 없음")
    void getSupportedCurrencies_정상() {
        SupportedCurrenciesResponse response = transferService.getSupportedCurrencies();

        // 1) 4개, 코드 집합이 정확히 KRW/USD/PHP/VND
        assertThat(response.getCurrencies())
                .as("지원 통화 4종이 모두 포함")
                .extracting(CurrencyItem::getCode)
                .containsExactlyInAnyOrder("KRW", "USD", "PHP", "VND");

        // 2) 각 항목의 code/name/symbol이 enum 정의와 1:1 일치
        assertThat(response.getCurrencies())
                .extracting(CurrencyItem::getCode, CurrencyItem::getName, CurrencyItem::getSymbol)
                .containsExactlyInAnyOrder(
                        tuple("KRW", "Korean Won",       "₩"),
                        tuple("USD", "US Dollar",        "$"),
                        tuple("PHP", "Philippine Peso",  "₱"),
                        tuple("VND", "Vietnamese Dong",  "₫"));

        // 3) 이 API는 DB/외부 안 본다는 설계를 코드로 못 박는다.
        verifyNoInteractions(walletRepository, transactionRepository, memberClient);
    }

    @Test
    @DisplayName("getRecentRemittanceAccounts 정상: bankAccount + amount/currency/receiverName + 마스킹 결합")
    void getRecentRemittanceAccounts_정상() {
        String SENDER = "sender-uuid";
        Wallet sender = wallet(1L, SENDER);
        long BANK_ACCOUNT_A = 101L;
        long BANK_ACCOUNT_B = 102L;
        LocalDateTime T_A = LocalDateTime.of(2026, 6, 3, 10, 0);
        LocalDateTime T_B = LocalDateTime.of(2026, 6, 2, 10, 0);

        given(walletRepository.findByUserPublicId(SENDER)).willReturn(Optional.of(sender));
        given(transactionRepository.findRecentRemittanceAccounts(eq(1L), any(Pageable.class)))
                .willReturn(List.of(
                        recentAccount(BANK_ACCOUNT_A, T_A),
                        recentAccount(BANK_ACCOUNT_B, T_B)));
        given(transactionRepository.findAmountsForLatestRemittances(
                eq(1L),
                eq(List.of(BANK_ACCOUNT_A, BANK_ACCOUNT_B)),
                eq(List.of(T_A, T_B))))
                .willReturn(List.of(
                        remittanceAmount(BANK_ACCOUNT_A, new BigDecimal("200000"), CurrencyType.KRW, "김민수",  T_A),
                        remittanceAmount(BANK_ACCOUNT_B, new BigDecimal("50"),     CurrencyType.USD, "Nguyen", T_B)));
        given(bankAccountRepository.findAllByIdIn(List.of(BANK_ACCOUNT_A, BANK_ACCOUNT_B)))
                .willReturn(List.of(
                        bankAccount(BANK_ACCOUNT_A, bank("KOOKMIN", "국민은행"), "1234567891111"),
                        bankAccount(BANK_ACCOUNT_B, bank("ACB",     "ACB Bank"), "987654321")));

        RecentAccountsResponse response = transferService.getRecentRemittanceAccounts(SENDER, 10);

        assertThat(response.getAccounts())
                .as("순서(A→B) + 모든 필드 매핑 + 마스킹(앞3 + 별표 + 뒤2, WACC-08) + 금액 string(스케일4) + ISO Z 시각")
                .extracting(AccountItem::getBankCode,
                            AccountItem::getBankName,
                            AccountItem::getAccountNumber,
                            AccountItem::getAccountHolder,
                            AccountItem::getCurrencyCode,
                            AccountItem::getLastAmount,
                            AccountItem::getLastTransferredAt)
                .containsExactly(
                        tuple("KOOKMIN", "국민은행",  "123********11", "김민수",  "KRW", "200000.0000", "2026-06-03T10:00:00Z"),
                        tuple("ACB",     "ACB Bank", "987****21",     "Nguyen", "USD", "50.0000",     "2026-06-02T10:00:00Z"));

        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("getRecentRemittanceAccounts 지갑 없음: WALLET4001 + 이후 호출 0회")
    void getRecentRemittanceAccounts_지갑_없음() {
        given(walletRepository.findByUserPublicId("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getRecentRemittanceAccounts("unknown", 10))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(transactionRepository, bankAccountRepository, memberClient);
    }

    @Test
    @DisplayName("getRecentRemittanceAccounts 빈 결과: accounts=[] + 보조 조회/BankAccount 미호출")
    void getRecentRemittanceAccounts_빈_결과() {
        String SENDER = "sender-uuid";
        Wallet sender = wallet(1L, SENDER);

        given(walletRepository.findByUserPublicId(SENDER)).willReturn(Optional.of(sender));
        given(transactionRepository.findRecentRemittanceAccounts(eq(1L), any(Pageable.class)))
                .willReturn(List.of());

        RecentAccountsResponse response = transferService.getRecentRemittanceAccounts(SENDER, 10);

        assertThat(response.getAccounts()).isEmpty();
        verifyNoInteractions(bankAccountRepository, memberClient);
    }

    @Test
    @DisplayName("getTransferFee REMITTANCE KRW 10000 → fee=50.0000, total=10050.0000, fee_currency=KRW")
    void getTransferFee_REMITTANCE_KRW_정상() {
        TransferFeeResponse response = transferService.getTransferFee(
                new TransferFeeRequest("REMITTANCE", "KRW", "10000.0000"));

        assertThat(response.getFee()).isEqualTo("50.0000");
        assertThat(response.getFeeCurrencyCode()).isEqualTo("KRW");
        assertThat(response.getTotalDeductAmount()).isEqualTo("10050.0000");

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee REMITTANCE USD 100 → fee=0.5000, total=100.5000, fee_currency=USD")
    void getTransferFee_REMITTANCE_USD_정상() {
        TransferFeeResponse response = transferService.getTransferFee(
                new TransferFeeRequest("REMITTANCE", "USD", "100.0000"));

        assertThat(response.getFee()).isEqualTo("0.5000");
        assertThat(response.getFeeCurrencyCode()).isEqualTo("USD");
        assertThat(response.getTotalDeductAmount()).isEqualTo("100.5000");

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee INTERNAL_TRANSFER → fee=0.0000, total=amount 그대로")
    void getTransferFee_INTERNAL_TRANSFER_정상() {
        TransferFeeResponse response = transferService.getTransferFee(
                new TransferFeeRequest("INTERNAL_TRANSFER", "KRW", "10000.0000"));

        assertThat(response.getFee()).isEqualTo("0.0000");
        assertThat(response.getFeeCurrencyCode()).isEqualTo("KRW");
        assertThat(response.getTotalDeductAmount()).isEqualTo("10000.0000");

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee 미지원 송금 유형(INVALID_TYPE) → BusinessException(TRANSFER4003)")
    void getTransferFee_미지원_송금_유형() {
        TransferFeeRequest req = new TransferFeeRequest("INVALID_TYPE", "KRW", "10000.0000");

        assertThatThrownBy(() -> transferService.getTransferFee(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee CHARGE/EXCHANGE는 TransactionType엔 있지만 수수료 API 허용 외 → TRANSFER4003")
    void getTransferFee_허용외_TransactionType_거부() {
        // TransactionType.CHARGE는 valueOf로 매칭되지만 ALLOWED_TRANSFER_TYPES 필터로 거부돼야 한다.
        TransferFeeRequest req = new TransferFeeRequest("CHARGE", "KRW", "10000.0000");

        assertThatThrownBy(() -> transferService.getTransferFee(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee 미지원 통화(EUR) → BusinessException(TRANSFER4002)")
    void getTransferFee_미지원_통화() {
        TransferFeeRequest req = new TransferFeeRequest("REMITTANCE", "EUR", "10000.0000");

        assertThatThrownBy(() -> transferService.getTransferFee(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY);

        verifyExternalsNotTouched();
    }

    @Test
    @DisplayName("getTransferFee BigDecimal 정밀도: 10000.5555 × 0.005 = 50.0027775 → HALF_UP scale 4 = 50.0028")
    void getTransferFee_BigDecimal_정밀도() {
        TransferFeeResponse response = transferService.getTransferFee(
                new TransferFeeRequest("REMITTANCE", "KRW", "10000.5555"));

        // 10000.5555 * 0.005 = 50.0027775 → HALF_UP scale 4 → 50.0028
        assertThat(response.getFee()).isEqualTo("50.0028");
        // total = 10000.5555 + 50.0028 = 10050.5583
        assertThat(response.getTotalDeductAmount()).isEqualTo("10050.5583");
        assertThat(response.getFeeCurrencyCode()).isEqualTo("KRW");

        verifyExternalsNotTouched();
    }

    /** 이 API가 DB/외부 호출을 안 한다는 설계를 모든 케이스에서 한 줄로 못 박는다. */
    private void verifyExternalsNotTouched() {
        verifyNoInteractions(walletRepository, transactionRepository,
                bankAccountRepository, memberClient, bankClient);
    }

    // ----- helpers -----

    /** Wallet은 GenerationType.IDENTITY라 단위 테스트에선 id를 reflection으로 직접 박는다. */
    private Wallet wallet(Long id, String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(w, "id", id);
        return w;
    }

    private RecentRecipientProjection recentRecipient(Long receiverId, LocalDateTime time) {
        return new RecentRecipientProjection() {
            @Override public Long getReceiverWalletId() { return receiverId; }
            @Override public LocalDateTime getLastTransferredAt() { return time; }
        };
    }

    private ReceiverCurrencyProjection receiverCurrency(Long receiverId, CurrencyType currency,
                                                        LocalDateTime createdAt) {
        return new ReceiverCurrencyProjection() {
            @Override public Long getReceiverWalletId() { return receiverId; }
            @Override public CurrencyType getCurrencyCode() { return currency; }
            @Override public LocalDateTime getCreatedAt() { return createdAt; }
        };
    }

    private RecentAccountProjection recentAccount(Long bankAccountId, LocalDateTime time) {
        return new RecentAccountProjection() {
            @Override public Long getBankAccountId() { return bankAccountId; }
            @Override public LocalDateTime getLastTransferredAt() { return time; }
        };
    }

    private RemittanceAmountProjection remittanceAmount(Long bankAccountId, BigDecimal amount,
                                                        CurrencyType currency, String receiverName,
                                                        LocalDateTime createdAt) {
        return new RemittanceAmountProjection() {
            @Override public Long getBankAccountId() { return bankAccountId; }
            @Override public BigDecimal getAmount() { return amount; }
            @Override public CurrencyType getCurrencyCode() { return currency; }
            @Override public String getReceiverName() { return receiverName; }
            @Override public LocalDateTime getCreatedAt() { return createdAt; }
        };
    }

    /** Bank entity는 builder 있음. id는 응답에 안 쓰이므로 reflection 생략. */
    private Bank bank(String code, String name) {
        return Bank.builder()
                .code(code)
                .name(name)
                .country("KR")
                .isDomestic(true)
                .isActive(true)
                .build();
    }

    /** BankAccount entity. id는 Service의 grouping map 키로 쓰이므로 reflection으로 박는다. */
    private BankAccount bankAccount(Long id, Bank bank, String accountNumber) {
        BankAccount account = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(UUID.randomUUID().toString())
                .bank(bank)
                .accountNumber(accountNumber)
                .holderName("NGUYEN VAN A")
                .isVirtual(false)
                .isPrimary(false)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    // ==========================================================================
    // getReceipt(userPublicId, transferPublicId) — 송금 확인증 조회
    // ==========================================================================

    private static final String TX_PUBLIC_ID = "tx-pid-9b2e4c1a";
    private static final LocalDateTime TX_CREATED = LocalDateTime.of(2026, 5, 25, 12, 0, 0);

    @Test
    @DisplayName("getReceipt 정상 INTERNAL_TRANSFER: 송신자/수신자 본명 채워서 응답, bank·account_number는 null")
    void getReceipt_정상_INTERNAL() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);
        com.gb.wallet.domain.transaction.entity.Transaction tx = buildTx(
                sender, com.gb.wallet.global.common.enums.TransactionType.INTERNAL_TRANSFER,
                "Nguyen Thi Linh", null);

        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.of(tx));
        given(memberClient.getMember(SENDER_PUBLIC_ID)).willReturn(
                new MemberInfo(SENDER_PUBLIC_ID, "sender@example.com",
                        "Sangam Beavers", "Sangam", "KR", true));

        var resp = transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID);

        assertThat(resp.publicId()).isEqualTo(TX_PUBLIC_ID);
        assertThat(resp.senderName()).as("본명을 응답해야 한다").isEqualTo("Sangam Beavers");
        assertThat(resp.receiverName()).as("snapshot된 수신자 본명을 그대로 응답").isEqualTo("Nguyen Thi Linh");
        assertThat(resp.bankName()).as("INTERNAL은 외부 은행 없음").isNull();
        assertThat(resp.accountNumber()).as("INTERNAL은 외부 계좌번호 없음").isNull();
        assertThat(resp.exchangeRate()).as("same-currency는 환율 null").isNull();
        assertThat(resp.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("getReceipt 정상 REMITTANCE: bankAccount에서 bank명 + 마스킹된 계좌번호 응답")
    void getReceipt_정상_REMITTANCE() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);
        com.gb.wallet.domain.transaction.entity.Transaction tx = buildTx(
                sender, com.gb.wallet.global.common.enums.TransactionType.REMITTANCE,
                "NGUYEN VAN A", 99L);
        BankAccount ba = bankAccount(99L, bank("020", "Quokka Bank"), "1002345678901");

        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.of(tx));
        given(bankAccountRepository.findWithBankById(99L)).willReturn(Optional.of(ba));
        given(memberClient.getMember(SENDER_PUBLIC_ID)).willReturn(
                new MemberInfo(SENDER_PUBLIC_ID, "sender@example.com",
                        "Sangam Beavers", "Sangam", "KR", true));

        var resp = transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID);

        assertThat(resp.senderName()).isEqualTo("Sangam Beavers");
        assertThat(resp.receiverName())
                .as("REMITTANCE는 외부 은행 verify 응답의 예금주를 그대로 snapshot")
                .isEqualTo("NGUYEN VAN A");
        assertThat(resp.bankName()).isEqualTo("Quokka Bank");
        assertThat(resp.accountNumber())
                .as("계좌번호는 응답 직전에 마스킹되어야 한다")
                .isNotEqualTo("1002345678901")
                .contains("*");
    }

    @Test
    @DisplayName("getReceipt: 거래 미존재 → TRANSFER4001 (정보 누설 방지)")
    void getReceipt_미존재_TRANSFER4001() {
        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        verifyNoInteractions(bankAccountRepository, memberClient);
    }

    @Test
    @DisplayName("getReceipt: 다른 사용자의 거래 → TRANSFER4001로 모호 매핑 (cross-user 차단)")
    void getReceipt_본인아님_TRANSFER4001() {
        Wallet otherSender = wallet(2L, "other-user-uuid");
        com.gb.wallet.domain.transaction.entity.Transaction tx = buildTx(
                otherSender, com.gb.wallet.global.common.enums.TransactionType.INTERNAL_TRANSFER,
                "Linh", null);
        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.of(tx));

        assertThatThrownBy(() -> transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        // 본인 검증 차단 후 부가 데이터 조회 진입 안 함
        verifyNoInteractions(bankAccountRepository, memberClient);
    }

    @Test
    @DisplayName("getReceipt: type=CHARGE 등 비송금 거래 → TRANSFER4001 (확인증 대상 아님)")
    void getReceipt_type_CHARGE_TRANSFER4001() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);
        com.gb.wallet.domain.transaction.entity.Transaction tx = buildTx(
                sender, com.gb.wallet.global.common.enums.TransactionType.CHARGE, null, null);
        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.of(tx));

        assertThatThrownBy(() -> transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        verifyNoInteractions(bankAccountRepository, memberClient);
    }

    @Test
    @DisplayName("getReceipt: status != COMPLETED(미완료 송금) → TRANSFER4001 (완료 송금만 확인증 대상)")
    void getReceipt_미완료상태_TRANSFER4001() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);
        com.gb.wallet.domain.transaction.entity.Transaction tx = buildTx(
                sender, com.gb.wallet.global.common.enums.TransactionType.INTERNAL_TRANSFER, "Linh", null);
        // 본인·유형은 통과하되 상태만 미완료(PENDING)로 둬 status 게이트만 단독 검증한다.
        ReflectionTestUtils.setField(tx, "status",
                com.gb.wallet.global.common.enums.TransactionStatus.PENDING);
        given(transactionRepository.findByPublicIdWithWallet(TX_PUBLIC_ID)).willReturn(Optional.of(tx));

        assertThatThrownBy(() -> transferService.getReceipt(SENDER_PUBLIC_ID, TX_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        verifyNoInteractions(bankAccountRepository, memberClient);
    }

    // ==========================================================================
    // validateScheduled(userPublicId, request) — 정기 송금 대상 유효성 검증
    // ==========================================================================

    private static final String BANK_ACC_PUB_ID = "bank-acc-pub-7g8h9i0j";
    private static final String RECEIVER_PUB_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("validateScheduled REMITTANCE 정상: 본인 활성 계좌 + 토큰 있음 + same-currency → is_valid=true")
    void validateScheduled_REMITTANCE_정상() {
        BankAccount account = bankAccount(50L, bank("020", "Quokka Bank"), "1002345678901");
        ReflectionTestUtils.setField(account, "mockAccountToken", "tok-abc");
        given(bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER_PUBLIC_ID))
                .willReturn(Optional.of(account));

        var resp = transferService.validateScheduled(SENDER_PUBLIC_ID, remittanceReq("KRW", "KRW"));

        assertThat(resp.isValid()).isTrue();
        assertThat(resp.reason()).isNull();
    }

    @Test
    @DisplayName("validateScheduled INTERNAL_TRANSFER 정상: 수신자 wallet 존재 + same-currency → is_valid=true")
    void validateScheduled_INTERNAL_정상() {
        given(walletRepository.findByUserPublicId(RECEIVER_PUB_ID))
                .willReturn(Optional.of(wallet(99L, RECEIVER_PUB_ID)));

        var resp = transferService.validateScheduled(SENDER_PUBLIC_ID, internalReq("KRW", "KRW"));

        assertThat(resp.isValid()).isTrue();
        assertThat(resp.reason()).isNull();
    }

    @Test
    @DisplayName("validateScheduled: same-currency 위반(KRW→VND) → 200 + is_valid=false + reason 메시지")
    void validateScheduled_currency_불일치_미통과() {
        BankAccount account = bankAccount(50L, bank("020", "Quokka Bank"), "1002345678901");
        ReflectionTestUtils.setField(account, "mockAccountToken", "tok-abc");
        given(bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER_PUBLIC_ID))
                .willReturn(Optional.of(account));

        var resp = transferService.validateScheduled(SENDER_PUBLIC_ID, remittanceReq("KRW", "VND"));

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.reason()).contains("같은 통화 송금", "3단계");
    }

    @Test
    @DisplayName("validateScheduled REMITTANCE: 본인/활성 계좌 미매칭 → ACCOUNT4001")
    void validateScheduled_REMITTANCE_계좌없음_ACCOUNT4001() {
        given(bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER_PUBLIC_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.validateScheduled(SENDER_PUBLIC_ID, remittanceReq("KRW", "KRW")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("validateScheduled REMITTANCE: 미인증 계좌(토큰 null) → ACCOUNT4006")
    void validateScheduled_REMITTANCE_미인증_ACCOUNT4006() {
        BankAccount account = bankAccount(50L, bank("020", "Quokka Bank"), "1002345678901");
        // mockAccountToken은 builder 기본값 null
        given(bankAccountRepository
                .findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER_PUBLIC_ID))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> transferService.validateScheduled(SENDER_PUBLIC_ID, remittanceReq("KRW", "KRW")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);
    }

    @Test
    @DisplayName("validateScheduled INTERNAL: 자기 자신 송금 → TRANSFER4004")
    void validateScheduled_INTERNAL_self_TRANSFER4004() {
        var req = new ValidateScheduledRequest(
                "INTERNAL_TRANSFER", SENDER_PUBLIC_ID, null, "10000.0000", "KRW", "KRW");

        assertThatThrownBy(() -> transferService.validateScheduled(SENDER_PUBLIC_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
    }

    @Test
    @DisplayName("validateScheduled: 미지원 통화(EUR) → TRANSFER4002")
    void validateScheduled_미지원통화_TRANSFER4002() {
        var req = new ValidateScheduledRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "10000.0000", "EUR", "EUR");

        assertThatThrownBy(() -> transferService.validateScheduled(SENDER_PUBLIC_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY);
    }

    private ValidateScheduledRequest remittanceReq(String currency, String receiveCurrency) {
        return new ValidateScheduledRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "500000.0000", currency, receiveCurrency);
    }

    private ValidateScheduledRequest internalReq(String currency, String receiveCurrency) {
        return new ValidateScheduledRequest(
                "INTERNAL_TRANSFER", RECEIVER_PUB_ID, null, "10000.0000", currency, receiveCurrency);
    }

    /** getReceipt 테스트용 Transaction 헬퍼. */
    private com.gb.wallet.domain.transaction.entity.Transaction buildTx(
            Wallet sender, com.gb.wallet.global.common.enums.TransactionType type,
            String receiverName, Long bankAccountId) {
        var tx = com.gb.wallet.domain.transaction.entity.Transaction.builder()
                .publicId(TX_PUBLIC_ID)
                .wallet(sender)
                .type(type)
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .fee(new BigDecimal("3000"))
                .status(com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED)
                .idempotencyKey("idem-receipt-" + TX_PUBLIC_ID)
                .receiverName(receiverName)
                .receiveAmount(new BigDecimal("500000"))
                .receiveCurrencyCode(CurrencyType.KRW)
                .bankAccountId(bankAccountId)
                .build();
        ReflectionTestUtils.setField(tx, "id", 100L);
        ReflectionTestUtils.setField(tx, "createdAt", TX_CREATED);
        return tx;
    }
}
