package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.service.impl.ChargeServiceImpl;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ChargeServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합·검증·예외·early-return,
 * 그리고 멱등성 래퍼(§5-1)의 정상/race 분기를 검증한다.
 *
 * <p>{@code doCharge}는 self-proxy 없이 직접 호출해 비즈니스 로직만 본다. {@code charge}(얇은 래퍼)는
 * {@code @Mock ChargeService self}를 통해 doCharge/readPrior 위임만 검증한다(트랜잭션 경계는 통합 테스트 책임).
 */
@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionAuditLogRepository auditLogRepository;
    @Mock private BankClient bankClient;
    @Mock private ChargeService self;
    @InjectMocks private ChargeServiceImpl service;

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER = "99999999-9999-9999-9999-999999999999";
    private static final String ACCT = "acct-public-id";
    private static final String TOKEN = "tok-abc";
    private static final String KEY = "idem-key-1";
    private static final String IP = "127.0.0.1";
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 5, 30, 4, 15, 30);

    @BeforeEach
    void injectSelf() {
        // @RequiredArgsConstructor(생성자 주입)를 쓰면 Mockito @InjectMocks는 생성자 주입만 수행하고
        // 비-final 필드(self)에 대한 추가 필드 주입을 하지 않는다 → self가 null로 남는다. 래퍼(charge)의
        // self-proxy 위임을 검증하려면 self 목을 직접 박아야 한다. doCharge 직접 호출 테스트는 self를
        // 참조하지 않으므로 영향 없음.
        ReflectionTestUtils.setField(service, "self", self);
    }

    // ===== doCharge — 정상 =====

    @Test
    @DisplayName("정상 충전: 잔액 0(신규 행) → 충전 후 amount. transaction/audit_log 저장 + 응답 검증")
    void doCharge_정상_신규잔액행() {
        BigDecimal amount = new BigDecimal("500000");
        Wallet wallet = wallet(USER);
        BankAccount account = account(TOKEN);

        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY)).willReturn(completed(amount));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.KRW))
                .willReturn(Optional.empty());
        given(walletBalanceRepository.save(any(WalletBalance.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L);
            ReflectionTestUtils.setField(t, "createdAt", FIXED);
            return t;
        });
        given(auditLogRepository.save(any(TransactionAuditLog.class))).willAnswer(inv -> inv.getArgument(0));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(amount), IP);

        // 신규 0원 행이 생성되고 amount만큼 증액됐는지
        ArgumentCaptor<WalletBalance> balCaptor = ArgumentCaptor.forClass(WalletBalance.class);
        verify(walletBalanceRepository).save(balCaptor.capture());
        assertThat(balCaptor.getValue().getBalance()).isEqualByComparingTo("500000");

        // 저장된 거래 검증
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction savedTx = txCaptor.getValue();
        assertThat(savedTx.getType()).isEqualTo(TransactionType.CHARGE);
        assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(savedTx.getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(savedTx.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(savedTx.getBankAccountId()).isEqualTo(1L);
        assertThat(savedTx.getFee()).isEqualByComparingTo("0");
        assertThat(savedTx.getAmount()).isEqualByComparingTo("500000");
        assertThat(savedTx.getPublicId()).isNotBlank();

        // 감사 로그 검증(before 0 → after 500000, append-only)
        ArgumentCaptor<TransactionAuditLog> logCaptor = ArgumentCaptor.forClass(TransactionAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        TransactionAuditLog log = logCaptor.getValue();
        assertThat(log.getAction()).isEqualTo("CHARGE");
        assertThat(log.getUserPublicId()).isEqualTo(USER);
        assertThat(log.getBeforeBalance()).isEqualByComparingTo("0");
        assertThat(log.getAfterBalance()).isEqualByComparingTo("500000");
        assertThat(log.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(log.getIpAddress()).isEqualTo(IP);
        assertThat(log.getTransaction()).isSameAs(savedTx);

        // 응답(명세 §12)
        assertThat(response.getPublicId()).isEqualTo(savedTx.getPublicId());
        assertThat(response.getAccountPublicId()).isEqualTo(ACCT);
        assertThat(response.getAmount()).isEqualTo("500000.0000");
        assertThat(response.getCurrencyCode()).isEqualTo("KRW");
        assertThat(response.getWalletBalance()).isEqualTo("500000.0000");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getCreatedAt()).isEqualTo("2026-05-30T04:15:30Z");
    }

    @Test
    @DisplayName("정상 충전: 기존 잔액 행이 있으면 그 행을 증액(신규 save 없음)")
    void doCharge_정상_기존잔액행_증액() {
        BigDecimal amount = new BigDecimal("300000");
        Wallet wallet = wallet(USER);
        WalletBalance existing = balance(wallet, new BigDecimal("1000000"));

        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY)).willReturn(completed(amount));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.KRW))
                .willReturn(Optional.of(existing));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "createdAt", FIXED);
            return t;
        });
        given(auditLogRepository.save(any(TransactionAuditLog.class))).willAnswer(inv -> inv.getArgument(0));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(amount), IP);

        assertThat(existing.getBalance()).as("기존 행이 직접 증액됨").isEqualByComparingTo("1300000");
        assertThat(response.getWalletBalance()).isEqualTo("1300000.0000");
        verify(walletBalanceRepository, never()).save(any()); // 기존 행은 dirty checking — save 호출 없음
    }

    @Test
    @DisplayName("한도 경계: 정확히 한도(1천만)면 통과(> 만 차단)")
    void doCharge_한도_경계_정확히_일치하면_통과() {
        BigDecimal amount = new BigDecimal("10000000");
        Wallet wallet = wallet(USER);

        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY)).willReturn(completed(amount));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.KRW))
                .willReturn(Optional.of(balance(wallet, BigDecimal.ZERO)));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "createdAt", FIXED);
            return t;
        });
        given(auditLogRepository.save(any(TransactionAuditLog.class))).willAnswer(inv -> inv.getArgument(0));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(amount), IP);

        assertThat(response.getWalletBalance()).isEqualTo("10000000.0000");
    }

    // ===== doCharge — 검증 실패(early return / 예외) =====

    @Test
    @DisplayName("계좌 없음/타인/비활성 → ACCOUNT4001, 은행·지갑 미조회")
    void doCharge_계좌없음_ACCOUNT4001() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        verifyNoInteractions(bankClient, walletRepository, walletBalanceRepository, auditLogRepository);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("mock_account_token null → ACCOUNT4006, Mock 호출 안 함")
    void doCharge_미인증계좌_ACCOUNT4006() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(null))); // 토큰 없음

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);

        verifyNoInteractions(bankClient, walletRepository, walletBalanceRepository, auditLogRepository);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("한도 초과(1천만 + 0.0001) → ACCOUNT4007, Mock·지갑 미접근")
    void doCharge_한도초과_ACCOUNT4007() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));

        assertThatThrownBy(() ->
                service.doCharge(USER, ACCT, KEY, request(new BigDecimal("10000000.0001")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.CHARGE_LIMIT_EXCEEDED);

        verifyNoInteractions(bankClient, walletRepository, walletBalanceRepository, auditLogRepository);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("지갑 없음 → WALLET4001, Mock 출금 전에 차단(외부 차감 방지)")
    void doCharge_지갑없음_WALLET4001() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(bankClient, walletBalanceRepository, auditLogRepository);
        verify(transactionRepository, never()).save(any());
    }

    // ===== doCharge — Mock 은행 에러 전파(잔액/거래 미저장) =====

    @Test
    @DisplayName("Mock BANK4002(잔액부족, ACCOUNT4003) 전파 + 잔액/거래 미저장")
    void doCharge_mock_ACCOUNT4003_전파() {
        assertMockErrorPropagates(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);
    }

    @Test
    @DisplayName("Mock BANK4010(토큰무효, ACCOUNT4006) 전파")
    void doCharge_mock_ACCOUNT4006_전파() {
        assertMockErrorPropagates(AccountErrorCode.UNVERIFIED_ACCOUNT);
    }

    @Test
    @DisplayName("Mock 5xx(COMMON5031) 전파")
    void doCharge_mock_COMMON5031_전파() {
        assertMockErrorPropagates(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Mock이 COMPLETED 아닌 상태를 200으로 반환 → COMMON5031(방어)")
    void doCharge_mock_비정상상태_COMMON5031() {
        BigDecimal amount = new BigDecimal("100");
        Wallet wallet = wallet(USER);
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY))
                .willReturn(new WithdrawalResult("t", "FAILED", amount, "KRW", BigDecimal.ZERO));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(amount), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(walletBalanceRepository, never()).findForUpdateByWalletAndCurrency(any(), any());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    // ===== doCharge — 멱등성 선검사(이미 처리된 키) =====

    @Test
    @DisplayName("멱등성: 이미 처리된 키면 첫 결과(audit_log의 after_balance) 재반환, Mock·저장 없음")
    void doCharge_멱등성_첫결과_재반환() {
        BigDecimal priorAmount = new BigDecimal("500000");
        Wallet wallet = wallet(USER);
        Transaction prior = priorTx(wallet, priorAmount);
        TransactionAuditLog priorLog = auditLog(prior, new BigDecimal("1500000")); // 당시 충전 후 잔액

        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));
        given(auditLogRepository.findFirstByTransaction_IdOrderByIdAsc(50L))
                .willReturn(Optional.of(priorLog));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(new BigDecimal("999")), IP);

        assertThat(response.getPublicId()).isEqualTo("prior-public-id");
        assertThat(response.getAccountPublicId()).isEqualTo(ACCT);
        assertThat(response.getAmount()).isEqualTo("500000.0000");
        assertThat(response.getWalletBalance()).as("현재 잔액이 아닌 당시 after_balance").isEqualTo("1500000.0000");

        verifyNoInteractions(bankClient, bankAccountRepository, walletRepository, walletBalanceRepository);
        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("멱등성: 다른 사용자의 키면 ACCOUNT4001(정보 누설 방지), audit_log 미조회")
    void doCharge_멱등성_다른사용자키_ACCOUNT4001() {
        Transaction prior = priorTx(wallet(OTHER_USER), new BigDecimal("500000"));
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        verifyNoInteractions(bankClient, bankAccountRepository, walletRepository, walletBalanceRepository);
        verify(auditLogRepository, never()).findFirstByTransaction_IdOrderByIdAsc(any());
    }

    @Test
    @DisplayName("readPrior: race로 들어왔는데 키가 사라진 정상 불가 상태면 COMMON5000")
    void readPrior_키없음_INTERNAL() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.readPrior(KEY, ACCT, USER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    // ===== charge — 멱등성 래퍼(§5-1) =====

    @Test
    @DisplayName("charge 정상: doCharge 결과를 그대로 반환(readPrior 미호출)")
    void charge_정상_위임() {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse expected = stubResponse();
        given(self.doCharge(USER, ACCT, KEY, req, IP)).willReturn(expected);

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(expected);
        verify(self).doCharge(USER, ACCT, KEY, req, IP);
        verify(self, never()).readPrior(any(), any(), any());
    }

    @Test
    @DisplayName("charge race: doCharge가 DataIntegrityViolationException 던지면 readPrior로 첫 결과 재반환")
    void charge_race_DataIntegrityViolation_readPrior로_복구() {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse prior = stubResponse();
        willThrow(new DataIntegrityViolationException("duplicate idempotency_key"))
                .given(self).doCharge(USER, ACCT, KEY, req, IP);
        given(self.readPrior(KEY, ACCT, USER)).willReturn(prior);

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(prior);
        verify(self).readPrior(KEY, ACCT, USER);
    }

    // ===== helpers =====

    private void assertMockErrorPropagates(com.gb.common.exception.ErrorCode mappedError) {
        BigDecimal amount = new BigDecimal("1000");
        Wallet wallet = wallet(USER);
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        willThrow(new BusinessException(mappedError))
                .given(bankClient).withdraw(TOKEN, amount, "KRW", KEY);

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(amount), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(mappedError);

        verify(walletBalanceRepository, never()).findForUpdateByWalletAndCurrency(any(), any());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    private BankAccount account(String token) {
        BankAccount a = BankAccount.builder()
                .publicId(ACCT)
                .userPublicId(USER)
                .accountNumber("1234567890")
                .mockAccountToken(token)
                .isVirtual(false)
                .isPrimary(true)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(a, "id", 1L);
        return a;
    }

    private Wallet wallet(String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId("wallet-" + userPublicId)
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(w, "id", 7L);
        return w;
    }

    private WalletBalance balance(Wallet wallet, BigDecimal initial) {
        return WalletBalance.builder()
                .wallet(wallet)
                .currencyCode(CurrencyType.KRW)
                .balance(initial)
                .build();
    }

    private ChargeRequest request(BigDecimal amount) {
        ChargeRequest r = new ChargeRequest();
        ReflectionTestUtils.setField(r, "amount", amount);
        return r;
    }

    private WithdrawalResult completed(BigDecimal amount) {
        return new WithdrawalResult("mock-tx", "COMPLETED", amount, "KRW", new BigDecimal("99999"));
    }

    private Transaction priorTx(Wallet wallet, BigDecimal amount) {
        Transaction t = Transaction.builder()
                .publicId("prior-public-id")
                .wallet(wallet)
                .type(TransactionType.CHARGE)
                .amount(amount)
                .currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(KEY)
                .bankAccountId(1L)
                .build();
        ReflectionTestUtils.setField(t, "id", 50L);
        ReflectionTestUtils.setField(t, "createdAt", FIXED);
        return t;
    }

    private TransactionAuditLog auditLog(Transaction tx, BigDecimal after) {
        return TransactionAuditLog.builder()
                .transaction(tx)
                .userPublicId(USER)
                .action("CHARGE")
                .amount(tx.getAmount())
                .currencyCode(CurrencyType.KRW)
                .beforeBalance(after.subtract(tx.getAmount()))
                .afterBalance(after)
                .status(TransactionStatus.COMPLETED)
                .build();
    }

    private ChargeResponse stubResponse() {
        return ChargeResponse.builder()
                .publicId("resp-public-id")
                .accountPublicId(ACCT)
                .amount("100.0000")
                .currencyCode("KRW")
                .walletBalance("100.0000")
                .status("COMPLETED")
                .createdAt("2026-05-30T04:15:30Z")
                .build();
    }
}