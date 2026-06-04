package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.service.ChargeAttemptWriter;
import com.gb.wallet.domain.account.service.impl.ChargeServiceImpl;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.config.ChargeProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ChargeServiceImpl} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이 조합·검증·예외·early-return,
 * 그리고 멱등성 래퍼의 정상/race·락경합 분기를 검증한다.
 *
 * <p>{@code doCharge}는 self-proxy 없이 직접 호출해 비즈니스 로직만 본다. {@code charge}(얇은 래퍼)는
 * {@code @Mock ChargeService self}를 통해 doCharge/readPrior 위임만 검증한다(트랜잭션 경계는 통합 테스트 책임).
 */
@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private WalletBalanceWriter walletBalanceWriter;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionAuditLogRepository auditLogRepository;
    @Mock private BankClient bankClient;
    @Mock private ChargeAttemptWriter chargeAttemptWriter;
    @Mock private IdempotencyCacheHelper idempotencyCacheHelper;
    @Mock private ObjectMapper objectMapper;
    @Mock private ChargeService self;
    @InjectMocks private ChargeServiceImpl service;

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER = "99999999-9999-9999-9999-999999999999";
    private static final String ACCT = "acct-public-id";
    private static final String TOKEN = "tok-abc";
    private static final String KEY = "idem-key-1";
    private static final String IP = "127.0.0.1";
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 5, 30, 4, 15, 30);

    /** 충전 Layer 1 캐시 키는 (key, user, account)로 스코프된다(ChargeServiceImpl.cacheKey와 동일 규칙). */
    private static final String CACHE_KEY = "charge:" + KEY + ":" + USER + ":" + ACCT;

    @BeforeEach
    void injectSelf() {
        // @RequiredArgsConstructor(생성자 주입)를 쓰면 Mockito @InjectMocks는 생성자 주입만 수행하고
        // 비-final 필드(self)에 대한 추가 필드 주입을 하지 않는다 → self가 null로 남는다. 래퍼(charge)의
        // self-proxy 위임을 검증하려면 self 목을 직접 박아야 한다. doCharge 직접 호출 테스트는 self를
        // 참조하지 않으므로 영향 없음.
        ReflectionTestUtils.setField(service, "self", self);
        // chargeProperties는 대응 @Mock이 없어 @InjectMocks 생성자 주입 시 null로 들어간다 → 한도 비교에서
        // NPE. mock이 아닌 실제 객체로 기본 한도(1천만원)를 박아 기존 동작을 유지한다(동작 불변 검증 목적).
        ReflectionTestUtils.setField(service, "chargeProperties",
                new ChargeProperties(new BigDecimal("10000000")));
    }

    // ===== doCharge — 정상 =====

    @Test
    @DisplayName("정상 충전: 잔액 행 없으면 WalletBalanceWriter로 0원 행 보장 후 잠그고 amount만큼 증액")
    void doCharge_정상_신규잔액행() {
        BigDecimal amount = new BigDecimal("500000");
        Wallet wallet = wallet(USER);
        BankAccount account = account(TOKEN);
        WalletBalance created = balance(wallet, BigDecimal.ZERO); // writer가 보장한 0원 행(재조회로 반환)

        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY)).willReturn(completed(amount));
        // ensureBalanceRow로 0원 행을 먼저 보장한 뒤, FOR UPDATE로 그 행을 한 번에 잠가서 반환한다
        // (ensure→FOR UPDATE 순서 — 없는 행에 FOR UPDATE를 걸면 gap lock+REQUIRES_NEW INSERT가 self-deadlock
        //  나므로 ensure를 먼저 한다. ChargeServiceImpl (7)단계 주석 참고).
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(wallet, CurrencyType.KRW))
                .willReturn(Optional.of(created));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L);
            ReflectionTestUtils.setField(t, "createdAt", FIXED);
            return t;
        });
        given(auditLogRepository.save(any(TransactionAuditLog.class))).willAnswer(inv -> inv.getArgument(0));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(amount), IP);

        // 행 생성은 writer에 위임(직접 save 아님), 보장된 행이 amount만큼 증액됐는지
        verify(walletBalanceWriter).ensureBalanceRow(wallet, CurrencyType.KRW);
        verify(walletBalanceRepository, never()).save(any());
        assertThat(created.getBalance()).isEqualByComparingTo("500000");

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

        // WACC-01 회귀: 외부 withdraw 직전에 시도 흔적을 기록한다(record가 withdraw보다 *먼저*).
        InOrder order = inOrder(chargeAttemptWriter, bankClient);
        order.verify(chargeAttemptWriter).record(KEY, USER, 1L, amount, CurrencyType.KRW);
        order.verify(bankClient).withdraw(TOKEN, amount, "KRW", KEY);
    }

    @Test
    @DisplayName("WTX-09: 은행 응답 금액이 요청과 불일치하면 COMMON5031, 잔액/거래 미저장(부분처리 차단)")
    void doCharge_은행응답_금액불일치_COMMON5031() {
        BigDecimal amount = new BigDecimal("100000");
        Wallet wallet = wallet(USER);
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        // status는 COMPLETED지만 은행이 처리한 금액이 요청(100000)과 다름(99000) → 정합성 깨짐.
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY))
                .willReturn(new WithdrawalResult("t", "COMPLETED", new BigDecimal("99000"), "KRW", BigDecimal.ZERO));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(amount), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        // 잔액 반영·거래/감사 저장 없음(잘못된 금액을 충전으로 확정하지 않는다).
        verify(walletBalanceRepository, never()).findForUpdateByWalletAndCurrency(any(), any());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("WTX-09: 은행 응답 통화가 요청과 불일치(USD)하면 COMMON5031, 거래 미저장")
    void doCharge_은행응답_통화불일치_COMMON5031() {
        BigDecimal amount = new BigDecimal("100000");
        Wallet wallet = wallet(USER);
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY))
                .willReturn(new WithdrawalResult("t", "COMPLETED", amount, "USD", BigDecimal.ZERO));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(amount), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("WTX-05: 충전 지갑이 SUSPENDED면 WALLET4003, Mock 출금/시도기록 전 차단(외부 차감 방지)")
    void doCharge_지갑_비활성_WALLET4003() {
        BigDecimal amount = new BigDecimal("100000");
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(suspendedWallet(USER)));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(amount), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_INACTIVE);

        // 지갑 상태 차단이 외부 출금·시도기록·잔액·저장 모두보다 먼저 (외부 차감 방지).
        verifyNoInteractions(bankClient, chargeAttemptWriter, walletBalanceWriter);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
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
    @DisplayName("외부화 검증: 설정 한도가 1000이면 1001 충전은 ACCOUNT4007 — 설정값이 실제 한도로 쓰임")
    void doCharge_설정한도_적용_초과시_ACCOUNT4007() {
        // 상수였다면 불가능했던 케이스: 한도를 1000으로 외부 주입하면 1001이 초과로 막혀야 한다.
        ReflectionTestUtils.setField(service, "chargeProperties",
                new ChargeProperties(new BigDecimal("1000")));
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("1001")), IP))
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

    @Test
    @DisplayName("Mock이 null 응답을 반환해도 NPE 없이 COMMON5031(방어)")
    void doCharge_mock_null응답_COMMON5031() {
        BigDecimal amount = new BigDecimal("100");
        Wallet wallet = wallet(USER);
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(ACCT, USER))
                .willReturn(Optional.of(account(TOKEN)));
        given(walletRepository.findByUserPublicId(USER)).willReturn(Optional.of(wallet));
        given(bankClient.withdraw(TOKEN, amount, "KRW", KEY)).willReturn(null);

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
        given(bankAccountRepository.findById(1L)).willReturn(Optional.of(account(TOKEN))); // prior 출금 계좌(publicId=ACCT)
        given(auditLogRepository.findFirstByTransaction_IdOrderByIdAsc(50L))
                .willReturn(Optional.of(priorLog));

        ChargeResponse response = service.doCharge(USER, ACCT, KEY, request(new BigDecimal("999")), IP);

        assertThat(response.getPublicId()).isEqualTo("prior-public-id");
        assertThat(response.getAccountPublicId()).isEqualTo(ACCT);
        assertThat(response.getAmount()).isEqualTo("500000.0000");
        assertThat(response.getWalletBalance()).as("현재 잔액이 아닌 당시 after_balance").isEqualTo("1500000.0000");

        verify(bankAccountRepository).findById(1L); // prior 계좌 일치 검증을 위해 1회 조회
        verifyNoInteractions(bankClient, walletRepository, walletBalanceRepository);
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
    @DisplayName("멱등성: 같은 키를 다른 계좌로 재사용하면 ACCOUNT4001(엉뚱한 계좌 응답 차단)")
    void doCharge_멱등성_다른계좌키_ACCOUNT4001() {
        Transaction prior = priorTx(wallet(USER), new BigDecimal("500000")); // bankAccountId=1L
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));
        given(bankAccountRepository.findById(1L)).willReturn(Optional.of(account(TOKEN))); // publicId=ACCT

        assertThatThrownBy(() ->
                service.doCharge(USER, "other-acct", KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        // 계좌 불일치라 응답 재구성(audit_log 조회)까지 가지 않는다.
        verify(auditLogRepository, never()).findFirstByTransaction_IdOrderByIdAsc(any());
        verifyNoInteractions(bankClient, walletRepository, walletBalanceRepository);
    }

    @Test
    @DisplayName("멱등성: 같은 키가 충전이 아닌 거래(예: 송금)면 ACCOUNT4001(유형 불일치 차단)")
    void doCharge_멱등성_충전아닌유형_ACCOUNT4001() {
        Transaction prior = Transaction.builder()
                .publicId("prior-transfer")
                .wallet(wallet(USER))
                .type(TransactionType.INTERNAL_TRANSFER) // CHARGE 아님
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(KEY)
                .build();
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.doCharge(USER, ACCT, KEY, request(new BigDecimal("100")), IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        // 유형 검증에서 막혀 계좌 조회·응답 재구성으로 가지 않는다.
        verify(auditLogRepository, never()).findFirstByTransaction_IdOrderByIdAsc(any());
        verifyNoInteractions(bankClient, bankAccountRepository, walletRepository, walletBalanceRepository);
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

    // ===== charge — 멱등성 래퍼(정상/race/락경합) =====

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

    @Test
    @DisplayName("charge 락경합: doCharge가 PessimisticLockingFailureException 1회 던지면 재시도해 성공 반환")
    void charge_락경합_재시도_성공() {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse expected = stubResponse();
        given(idempotencyCacheHelper.get(CACHE_KEY)).willReturn(Optional.empty());
        // 1차: 락 경합으로 롤백(CannotAcquireLockException) → 2차: 성공
        given(self.doCharge(USER, ACCT, KEY, req, IP))
                .willThrow(new CannotAcquireLockException("lock timeout"))
                .willReturn(expected);

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(expected);
        verify(self, times(2)).doCharge(USER, ACCT, KEY, req, IP);
        verify(self, never()).readPrior(any(), any(), any()); // 락경합은 readPrior가 아니라 재시도로 복구
    }

    @Test
    @DisplayName("charge 락경합: 재시도(최대 3회) 모두 실패하면 COMMON5031(503), 캐시 미저장")
    void charge_락경합_재시도소진_COMMON5031() {
        ChargeRequest req = request(new BigDecimal("100"));
        given(idempotencyCacheHelper.get(CACHE_KEY)).willReturn(Optional.empty());
        given(self.doCharge(USER, ACCT, KEY, req, IP))
                .willThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> service.charge(USER, ACCT, KEY, req, IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(self, times(3)).doCharge(USER, ACCT, KEY, req, IP); // MAX_CHARGE_ATTEMPTS
        verify(self, never()).readPrior(any(), any(), any());
        verify(idempotencyCacheHelper, never()).set(any(), any()); // 에러 응답은 캐시에 넣지 않는다
    }

    // ===== charge — 멱등성 Layer 1(Redis 캐시) =====

    @Test
    @DisplayName("charge Layer 1: 캐시 hit이면 doCharge/readPrior·캐시쓰기 없이 캐시 응답 그대로 반환")
    void charge_캐시_hit() throws Exception {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse cached = stubResponse();
        // (key, user, account)로 스코프된 키로 조회한다.
        given(idempotencyCacheHelper.get(CACHE_KEY)).willReturn(Optional.of("{\"cached\":\"json\"}"));
        given(objectMapper.readValue("{\"cached\":\"json\"}", ChargeResponse.class)).willReturn(cached);

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(cached);
        verify(self, never()).doCharge(any(), any(), any(), any(), any());
        verify(self, never()).readPrior(any(), any(), any());
        verify(idempotencyCacheHelper, never()).set(any(), any());
    }

    @Test
    @DisplayName("charge Layer 1: 캐시 miss → doCharge 성공 시 결과를 스코프 키로 캐시에 채운다(set 호출)")
    void charge_캐시_miss_후_채움() throws Exception {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse expected = stubResponse();
        given(idempotencyCacheHelper.get(CACHE_KEY)).willReturn(Optional.empty());
        given(self.doCharge(USER, ACCT, KEY, req, IP)).willReturn(expected);
        given(objectMapper.writeValueAsString(expected)).willReturn("{\"json\":\"ok\"}");

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(expected);
        verify(idempotencyCacheHelper).set(CACHE_KEY, "{\"json\":\"ok\"}");
    }

    @Test
    @DisplayName("charge Layer 1: 같은 key라도 다른 사용자는 캐시 키가 분리돼 캐시를 우회하고 doCharge로 간다(교차 사용자 격리)")
    void charge_캐시_사용자_격리() {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse expected = stubResponse();
        String otherUserKey = "charge:" + KEY + ":" + OTHER_USER + ":" + ACCT;
        given(idempotencyCacheHelper.get(otherUserKey)).willReturn(Optional.empty());
        given(self.doCharge(OTHER_USER, ACCT, KEY, req, IP)).willReturn(expected);

        ChargeResponse result = service.charge(OTHER_USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(expected);
        // USER가 저장했을 캐시 키(CACHE_KEY)는 조회조차 하지 않는다 — 다른 사용자는 캐시 격리.
        verify(idempotencyCacheHelper, never()).get(CACHE_KEY);
        verify(self).doCharge(OTHER_USER, ACCT, KEY, req, IP);
    }

    @Test
    @DisplayName("charge Layer 1: 캐시 조회가 Redis 예외로 실패해도 막지 않고 doCharge로 폴백(fail-safe)")
    void charge_캐시조회_실패_폴백() {
        ChargeRequest req = request(new BigDecimal("100"));
        ChargeResponse expected = stubResponse();
        given(idempotencyCacheHelper.get(CACHE_KEY))
                .willThrow(new RuntimeException("redis down"));
        given(self.doCharge(USER, ACCT, KEY, req, IP)).willReturn(expected);

        ChargeResponse result = service.charge(USER, ACCT, KEY, req, IP);

        assertThat(result).isSameAs(expected);
        verify(self).doCharge(USER, ACCT, KEY, req, IP);
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

    /** WTX-05 테스트용 SUSPENDED(동결) 지갑. */
    private Wallet suspendedWallet(String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId("wallet-" + userPublicId)
                .userPublicId(userPublicId)
                .status(WalletStatus.SUSPENDED)
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