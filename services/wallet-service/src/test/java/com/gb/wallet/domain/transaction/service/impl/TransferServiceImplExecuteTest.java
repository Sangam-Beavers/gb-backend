package com.gb.wallet.domain.transaction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.RemittanceAttemptWriter;
import com.gb.wallet.domain.transaction.service.TransferPinGate;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.client.dto.PayoutResult;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.config.TransferRateLimitProperties;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
import com.gb.wallet.global.redis.RateLimitHelper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * {@link TransferServiceImpl#execute} 단위 테스트(Mockito). DB·Spring 컨텍스트 없이
 * 입력 검증·도메인 검증·잔액 변경·audit log INSERT·멱등성 3-layer·분산 락 분기를 검증한다.
 *
 * <p>self-injection은 {@code service} 자기 자신을 주입해 {@code execute → self.executeInTransaction}와
 * {@code executeInTransaction catch → self.readPriorTransaction} 호출이 진짜 메서드 본문을 타도록 한다
 * (ChargeServiceTest는 {@code @Mock self}로 위임만 보지만, 송금은 한 메서드 안에 멱등성/락/처리가 얽혀 있어
 * 실제 흐름을 검증해야 한다). 트랜잭션 경계({@code @Transactional} AOP)는 단위 테스트 범위 밖.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceImplExecuteTest {

    @Mock private WalletRepository walletRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private WalletBalanceWriter walletBalanceWriter;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionAuditLogRepository auditLogRepository;
    @Mock private RemittanceAttemptWriter remittanceAttemptWriter;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private MemberClient memberClient;
    @Mock private BankClient bankClient;
    @Mock private DistributedLockHelper distributedLockHelper;
    @Mock private IdempotencyCacheHelper idempotencyCacheHelper;
    @Mock private RateLimitHelper rateLimitHelper;
    @Mock private TransferRateLimitProperties transferRateLimitProperties;
    @Mock private TransferPinGate transferPinGate;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private TransferServiceImpl service;

    private static final String SENDER_USER = "sender-user-uuid";
    private static final String RECEIVER_USER = "receiver-user-uuid";
    private static final String KEY = "idem-key-1";
    private static final long SENDER_WALLET_ID = 7L;
    private static final long RECEIVER_WALLET_ID = 13L;
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 6, 1, 4, 15, 30);

    @BeforeEach
    void injectSelf() {
        // @RequiredArgsConstructor 생성자 주입 + 비-final self 필드는 @InjectMocks가 채우지 않는다.
        // self.executeInTransaction / self.readPriorTransaction이 진짜 메서드를 타도록 자기 자신을 주입한다.
        // (Mock TransferService를 self로 박으면 위임 검증만 가능 — 송금은 catch 안 분기까지 봐야 함)
        ReflectionTestUtils.setField(service, "self", service);

        // Rate-limit은 기본 통과(true) — execute() 최상단에서 차단되지 않도록. 초과 시나리오는 개별 테스트에서 willReturn(false)로 덮어쓴다.
        // lenient=false(기본)는 stubbed 호출이 한 번도 안 일어나면 strict 모드라 깨지므로, lenient()로 만든다.
        Mockito.lenient().when(rateLimitHelper.tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class)))
                .thenReturn(true);
        Mockito.lenient().when(transferRateLimitProperties.limit()).thenReturn(30);
        Mockito.lenient().when(transferRateLimitProperties.windowSeconds()).thenReturn(60);
    }

    // ===== 정상 흐름 =====

    @Test
    @DisplayName("정상 송금: 잔액 차감/증액 + Transaction 1 + audit log 2(SEND/RECEIVE) + Redis 캐시 저장 + 락 해제")
    void execute_정상() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);
        RLock lock = lock();

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock);
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{\"any\":\"json\"}");

        TransferExecuteResponse response = service.execute(SENDER_USER, KEY, request("10000.0000"));

        // 응답 검증
        assertThat(response.amount()).isEqualTo("10000.0000");
        assertThat(response.fee()).isEqualTo("0.0000");
        assertThat(response.receiveAmount()).isEqualTo("10000.0000");
        assertThat(response.currencyCode()).isEqualTo("KRW");
        assertThat(response.receiveCurrencyCode()).isEqualTo("KRW");
        assertThat(response.transferType()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.exchangeRate()).as("1단계 같은 통화는 환율 null").isNull();

        // 잔액 변경
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("990000");
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo("10000");

        // Transaction 1건 + audit log 2건(SEND/RECEIVE 액션)
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        ArgumentCaptor<TransactionAuditLog> logCaptor = ArgumentCaptor.forClass(TransactionAuditLog.class);
        verify(auditLogRepository, times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .extracting(TransactionAuditLog::getAction, TransactionAuditLog::getUserPublicId,
                            l -> l.getBeforeBalance().compareTo(l.getAfterBalance()))
                .containsExactly(
                        tuple("INTERNAL_TRANSFER_SEND",    SENDER_USER,   1),  // before > after (차감)
                        tuple("INTERNAL_TRANSFER_RECEIVE", RECEIVER_USER, -1)); // before < after (증액)

        // 캐시 저장 + 락 해제. 캐시 키는 (도메인, key, user, scope) 4-튜플 스코프 형식 명시 검증
        // — cross-user/scope 노출 방지가 P0 보안 결정적이라 ArgumentCaptor로 키 형식까지 확정.
        ArgumentCaptor<String> internalCacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyCacheHelper).set(internalCacheKeyCaptor.capture(), anyString());
        assertThat(internalCacheKeyCaptor.getValue())
                .as("INTERNAL_TRANSFER 캐시 키는 4-튜플 스코프 형식: internal_transfer:{key}:{user}:{receiver_user}")
                .contains("internal_transfer", KEY, SENDER_USER, RECEIVER_USER);
        verify(lock).unlock();

        // 회귀 가드: INTERNAL_TRANSFER 경로는 REMITTANCE 시도 흔적을 박지 않는다(분기 누수 방지).
        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());

        // TX-PIN: 사용자 직접 호출은 자금 이동 전에 PIN 게이트를 통과해야 한다(마커 원자 소비).
        verify(transferPinGate).requireVerified(SENDER_USER);
    }

    @Test
    @DisplayName("wallet-transfer-2: 수신자 본명 조회(MemberClient)는 분산락·FOR UPDATE보다 *먼저* — 락 보유 중 외부 HTTP 제거")
    void execute_INTERNAL_receiverName조회_락보다_먼저() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);
        RLock lock = lock();

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock);
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");
        given(memberClient.getMember(RECEIVER_USER))
                .willReturn(new MemberInfo(RECEIVER_USER, "r@example.com", "수취인본명", "닉", "VN", true));

        service.execute(SENDER_USER, KEY, request("10000.0000"));

        // 핵심: getMember(외부 HTTP)가 분산락 획득보다 *먼저* 호출된다 — 락은 그 뒤에 잡히고 FOR UPDATE는
        //   다시 그 안에서 일어나므로, 외부 HTTP가 락/FOR UPDATE 보유 구간 밖이라는 것이 증명된다(wallet-transfer-2).
        InOrder inOrder = Mockito.inOrder(memberClient, distributedLockHelper);
        inOrder.verify(memberClient).getMember(RECEIVER_USER);
        inOrder.verify(distributedLockHelper).tryLockTwoWallets(SENDER_WALLET_ID, RECEIVER_WALLET_ID);
        // 조회한 본명이 Transaction에 snapshot됐는지 확인(락 밖 조회값이 그대로 전달됨).
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getReceiverName()).isEqualTo("수취인본명");
    }

    @Test
    @DisplayName("wallet-transfer-2: MemberClient 장애여도 fail-open — receiverName=null로 송금 정상 완료")
    void execute_INTERNAL_memberClient장애_failOpen() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");
        given(memberClient.getMember(RECEIVER_USER)).willThrow(new RuntimeException("member-service down"));

        TransferExecuteResponse response = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(response.status()).isEqualTo("COMPLETED"); // 외부 장애가 송금을 막지 않는다
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getReceiverName()).isNull(); // fail-open → null snapshot
    }

    @Test
    @DisplayName("charge-2: INTERNAL 수신자 ensureBalanceRow가 UnexpectedRollbackException(동시 race)여도 흡수하고 송금 완료")
    void execute_INTERNAL_ensure_UnexpectedRollback_흡수() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");
        // 동시 같은 (wallet,currency) race로 REQUIRES_NEW가 rollback-only → 커밋 시 UnexpectedRollbackException(행은 이미 존재).
        willThrow(new UnexpectedRollbackException("rollback-only"))
                .given(walletBalanceWriter).ensureBalanceRow(receiver, CurrencyType.KRW);

        TransferExecuteResponse response = service.execute(SENDER_USER, KEY, request("10000.0000"));

        // 흡수돼 송금은 정상 완료(generic 500 아님) — 0원 행은 경쟁 INSERT로 이미 존재해 FOR UPDATE가 잠근다.
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("990000");
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo("10000");
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("charge-2: REMITTANCE remittanceAttemptWriter.record가 UnexpectedRollbackException여도 흡수하고 payout 진행")
    void execute_REMITTANCE_record_UnexpectedRollback_흡수() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        // 동시 같은 키 race로 REQUIRES_NEW가 rollback-only → 커밋 시 UnexpectedRollbackException(흔적은 이미 존재).
        willThrow(new UnexpectedRollbackException("rollback-only"))
                .given(remittanceAttemptWriter).record(any(), any(), any(), any(), any());
        given(bankClient.payout(eq(BANK_CODE), eq(BANK_ACCOUNT_NUMBER), any(), any(), eq(KEY)))
                .willReturn(new PayoutResult("mock-payout-1", "COMPLETED", new BigDecimal("10000.0000"), "KRW",
                        new BigDecimal("500000.0000")));
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse response = service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID));

        // 흔적 race 예외가 흡수돼 외부 payout까지 진행하고 송금이 완료된다.
        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(bankClient).payout(eq(BANK_CODE), eq(BANK_ACCOUNT_NUMBER), any(), any(), eq(KEY));
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("수신자가 해당 통화 잔액 행이 없던 회원이어도 → ensure가 0원 행 생성 후 송금 성공")
    void execute_수신자_zero_balance_ensure_생성_후_송금_성공() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        // 의도(zero-start 입금) 문서화: 첫 KRW 수신자라 ensure가 막 INSERT한 0원 행이 FOR UPDATE에 잡힌다고
        // 가정한다. walletBalanceWriter는 @Mock 이라 void 호출이 no-op — 실제 INSERT/REQUIRES_NEW/race 흡수는
        // 단위 테스트 mock으로 검증 불가하므로 Testcontainers 백로그(통합 테스트 단계)에서 보강한다.
        WalletBalance receiverZero = balance(receiver, BigDecimal.ZERO);
        RLock lock = lock();

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock);
        stubBalanceLocks(sender, receiver, senderBalance, receiverZero);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{\"any\":\"json\"}");

        TransferExecuteResponse response = service.execute(SENDER_USER, KEY, request("10000.0000"));

        // 응답: COMPLETED + 1단계 정책(fee=0, exchange null, receive_amount = amount)
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.amount()).isEqualTo("10000.0000");
        assertThat(response.fee()).isEqualTo("0.0000");
        assertThat(response.exchangeRate()).isNull();

        // 핵심 신호: 수신자에 대해 ensure가 FOR UPDATE 이전에 호출됐다.
        verify(walletBalanceWriter).ensureBalanceRow(receiver, CurrencyType.KRW);

        // 잔액 변경: sender 1000000 → 990000, receiver 0 → 10000 (fee=0)
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("990000");
        assertThat(receiverZero.getBalance()).isEqualByComparingTo("10000");

        // Transaction 1건 + audit log 2건(SEND/RECEIVE)
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(auditLogRepository, times(2)).save(any(TransactionAuditLog.class));
    }

    // ===== 도메인 검증 실패 =====

    @Test
    @DisplayName("잔액 부족 → WALLET4002, 거래/감사 로그 저장 0회")
    void execute_잔액부족_WALLET4002() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("5000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.INSUFFICIENT_BALANCE);

        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("자기 송금 → TRANSFER4004, 분산 락 진입 전 차단")
    void execute_자기송금_TRANSFER4004() {
        // 같은 wallet id로 sender == receiver 시뮬레이션 (user_public_id가 같으면 같은 지갑 1개 → 같은 ID 리턴)
        Wallet self = wallet(SENDER_WALLET_ID, SENDER_USER);
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(self));

        TransferExecuteRequest req = new TransferExecuteRequest(
                "INTERNAL_TRANSFER", "10000.0000", "KRW", "KRW", null, SENDER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);

        verifyNoInteractions(distributedLockHelper);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("WTX-05: INTERNAL 송신자 지갑 SUSPENDED → WALLET4003, 분산 락/저장 미진입")
    void execute_INTERNAL_송신자_비활성_WALLET4003() {
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER))
                .willReturn(Optional.of(suspendedWallet(SENDER_WALLET_ID, SENDER_USER)));
        given(walletRepository.findByUserPublicId(RECEIVER_USER))
                .willReturn(Optional.of(wallet(RECEIVER_WALLET_ID, RECEIVER_USER)));

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_INACTIVE);

        verifyNoInteractions(distributedLockHelper);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("WTX-05: INTERNAL 수신자 지갑 SUSPENDED → WALLET4003(비활성 지갑 입금 차단)")
    void execute_INTERNAL_수신자_비활성_WALLET4003() {
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER))
                .willReturn(Optional.of(wallet(SENDER_WALLET_ID, SENDER_USER)));
        given(walletRepository.findByUserPublicId(RECEIVER_USER))
                .willReturn(Optional.of(suspendedWallet(RECEIVER_WALLET_ID, RECEIVER_USER)));

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_INACTIVE);

        verifyNoInteractions(distributedLockHelper);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("WTX-05: REMITTANCE 송신자 지갑 SUSPENDED → WALLET4003, payout/Writer 미호출")
    void execute_REMITTANCE_송신자_비활성_WALLET4003() {
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER))
                .willReturn(Optional.of(suspendedWallet(SENDER_WALLET_ID, SENDER_USER)));
        given(walletRepository.findById(SENDER_WALLET_ID))
                .willReturn(Optional.of(suspendedWallet(SENDER_WALLET_ID, SENDER_USER)));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_INACTIVE);

        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
        verifyNoInteractions(bankClient);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 통화 송금(currency != receive) → TRANSFER4005, wallet 조회 전 차단")
    void execute_다른통화_TRANSFER4005() {
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "INTERNAL_TRANSFER", "10000.0000", "KRW", "USD", null, RECEIVER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY_PAIR);

        verify(walletRepository, never()).findByUserPublicId(anyString());
        verifyNoInteractions(distributedLockHelper);
    }

    @Test
    @DisplayName("미지원 통화(EUR) → TRANSFER4002")
    void execute_미지원통화_TRANSFER4002() {
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "INTERNAL_TRANSFER", "10000.0000", "EUR", "EUR", null, RECEIVER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY);
    }

    @Test
    @DisplayName("미지원 송금 유형(CHARGE) → TRANSFER4003")
    void execute_미지원송금유형_CHARGE_TRANSFER4003() {
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "CHARGE", "10000.0000", "KRW", "KRW", null, RECEIVER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
    }

    @Test
    @DisplayName("REMITTANCE: bank_account_public_id 누락 → COMMON4001 (분기 진입 후 REMITTANCE 필수 필드 검증)")
    void execute_REMITTANCE_bankAccountPublicId_누락_COMMON4001() {
        // 2단계에서 REMITTANCE 분기를 열었으므로 type 자체는 통과한다.
        // 다만 bank_account_public_id가 비어 있으면 REMITTANCE 사전 검증(executeRemittancePath)에서 차단된다.
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "REMITTANCE", "10000.0000", "KRW", "KRW", null, RECEIVER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("TX1: INTERNAL_TRANSFER receiver_public_id 누락 → COMMON4001 (@NotBlank 제거 후 도메인 검증으로 차단)")
    void execute_INTERNAL_receiverPublicId_누락_COMMON4001() {
        // TX1: DTO 무조건 @NotBlank를 제거했으므로 receiver 누락이 @Valid를 통과해 서비스에 도달한다.
        //      resolveScopeId의 INTERNAL 분기(REMITTANCE 대칭)가 COMMON4001로 차단한다.
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "INTERNAL_TRANSFER", "10000.0000", "KRW", "KRW", null, null, null); // receiverPublicId=null

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("송신자 wallet 없음 → WALLET4001")
    void execute_송신자_wallet_없음_WALLET4001() {
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("수신자 wallet 없음 → WALLET4001")
    void execute_수신자_wallet_없음_WALLET4001() {
        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER))
                .willReturn(Optional.of(wallet(SENDER_WALLET_ID, SENDER_USER)));
        given(walletRepository.findByUserPublicId(RECEIVER_USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("송신자 잔액 행 없음 → WALLET4001 (송신자는 자동 생성 안 함 — 돈 있어야 보냄)")
    void execute_송신자_잔액행없음_WALLET4001() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        // 수신자 행은 ensure가 보장한 0원 행 — 정상 존재. 송신자 행만 없음.
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(walletRepository.findById(RECEIVER_WALLET_ID)).willReturn(Optional.of(receiver));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.empty());
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(receiver, CurrencyType.KRW))
                .willReturn(Optional.of(receiverBalance));

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        // 수신자 ensure는 FOR UPDATE 이전 단계에서 호출됐어야 한다(이 케이스에선 receiver는 정상 행).
        verify(walletBalanceWriter).ensureBalanceRow(receiver, CurrencyType.KRW);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("수신자 잔액 행이 ensure 후에도 없음 → COMMON5000 (정합성 비정상)")
    void execute_수신자_잔액행없음_post_ensure_COMMON5000() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(walletRepository.findById(RECEIVER_WALLET_ID)).willReturn(Optional.of(receiver));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        // ensure를 (mock) 호출했음에도 receiver 행이 안 보이는 비정상 상태 시뮬레이션.
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(receiver, CurrencyType.KRW))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);

        verify(walletBalanceWriter).ensureBalanceRow(receiver, CurrencyType.KRW);
        verify(transactionRepository, never()).save(any());
    }

    // ===== 멱등성 3-layer =====

    @Test
    @DisplayName("Layer 1(Redis cache hit): 캐시된 응답 그대로 반환, DB/락/외부 호출 0회")
    void execute_Layer1_cacheHit() throws Exception {
        TransferExecuteResponse cached = stubCachedResponse();
        // 스코프된 캐시 키(anyString) hit. 키 스코프 정확성은 별도 케이스에서 검증.
        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.of("{\"cached\":\"json\"}"));
        given(objectMapper.readValue(anyString(), eq(TransferExecuteResponse.class))).willReturn(cached);

        TransferExecuteResponse result = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(walletRepository, walletBalanceRepository, distributedLockHelper,
                auditLogRepository, bankClient, memberClient, bankAccountRepository,
                remittanceAttemptWriter);
        // TX-PIN: 멱등 replay(캐시 hit)는 게이트 이전에 반환 — PIN 재검증을 요구하지 않는다(마커 미소비).
        verifyNoInteractions(transferPinGate);
        verify(transactionRepository, never()).findByIdempotencyKey(anyString());
        // replay 경로는 executeInTransaction 미진입 — ensure도 호출되면 안 된다.
        verify(walletBalanceWriter, never()).ensureBalanceRow(any(), any());
        // WTX-04: 멱등 replay(캐시 hit)는 신규 처리가 아니므로 rate-limit 토큰을 소모하지 않는다.
        verify(rateLimitHelper, never()).tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("Layer 2(Redis miss + DB hit): DB 거래 1건 그대로 반환 + 캐시 채움, 락/외부 호출 0회")
    void execute_Layer2_dbHit() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Transaction prior = priorTransaction(sender);

        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse result = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(result.amount()).isEqualTo("10000.0000");
        assertThat(result.status()).isEqualTo("COMPLETED");
        // 락/외부 호출 미진입
        verifyNoInteractions(distributedLockHelper, walletBalanceRepository, auditLogRepository);
        // TX-PIN: Layer 2 멱등 재반환도 게이트 이전에 반환 — PIN 재검증을 요구하지 않는다.
        verifyNoInteractions(transferPinGate);
        verify(walletRepository, never()).findByUserPublicId(anyString());
        // 캐시 채움(다음 동일 키 요청은 Layer 1로 처리). 키는 스코프된 형태(anyString).
        verify(idempotencyCacheHelper).set(anyString(), anyString());
        // replay 경로는 executeInTransaction 미진입 — ensure도 호출되면 안 된다.
        verify(walletBalanceWriter, never()).ensureBalanceRow(any(), any());
        // 회귀 가드: replay는 어떤 분기로도 흔적을 박지 않는다.
        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
        // WTX-04: Layer 2 멱등 재반환도 신규 처리가 아니므로 rate-limit 토큰을 소모하지 않는다.
        verify(rateLimitHelper, never()).tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("Layer 3(UNIQUE 위반 catch): readPriorTransaction이 별도 호출돼 첫 결과 재반환")
    void execute_Layer3_uniqueViolation_readPrior() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);
        Transaction prior = priorTransaction(sender);

        stubCacheMiss();
        // findByIdempotencyKey: Layer 2 첫 호출은 empty(통과), readPriorTransaction 두 번째 호출은 prior 반환
        given(transactionRepository.findByIdempotencyKey(KEY))
                .willReturn(Optional.empty()).willReturn(Optional.of(prior));
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        // transactionRepository.save에서 UNIQUE 위반 race
        given(transactionRepository.save(any(Transaction.class)))
                .willThrow(new DataIntegrityViolationException("duplicate idempotency_key"));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse result = service.execute(SENDER_USER, KEY, request("10000.0000"));

        // prior 거래 결과가 그대로 반환됨
        assertThat(result.amount()).isEqualTo("10000.0000");
        assertThat(result.status()).isEqualTo("COMPLETED");

        // findByIdempotencyKey 2회 — Layer 2 검사 + readPriorTransaction
        verify(transactionRepository, times(2)).findByIdempotencyKey(KEY);
        // audit log는 race 전에 save가 터져 저장되지 않음
        verify(auditLogRepository, never()).save(any());
        // 회귀 가드: INTERNAL_TRANSFER 분기는 REMITTANCE 흔적을 박지 않는다.
        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
    }

    // ===== 분산 락 =====

    @Test
    @DisplayName("분산 락 획득 실패(tryLockTwoWallets=null) → COMMON5031, 작업 진입 0")
    void execute_lockFail_COMMON5031() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        given(distributedLockHelper.tryLockTwoWallets(SENDER_WALLET_ID, RECEIVER_WALLET_ID))
                .willReturn(null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verifyNoInteractions(walletBalanceRepository, auditLogRepository);
        verify(transactionRepository, never()).save(any());
    }

    // ===== REMITTANCE 분기 (2단계) =====

    private static final String BANK_ACCOUNT_PUB_ID = "11111111-2222-3333-4444-555555555555";
    private static final long BANK_ACCOUNT_ID = 99L;
    private static final String BANK_CODE = "VCB";
    private static final String BANK_ACCOUNT_NUMBER = "9876543210";
    private static final String MOCK_TOKEN = "mock-token-abc";

    @Test
    @DisplayName("REMITTANCE 성공: Writer.record → bankClient.payout 순서 + 송신자 잔액 (amount+fee) 차감 + Transaction/audit 1건씩")
    void execute_REMITTANCE_성공_attempt기록후_payout실행후_차감() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);
        // REMITTANCE: KRW 10000 → fee = 50.0000, totalDeduct = 10050.0000
        BigDecimal expectedTotalDeduct = new BigDecimal("10050.0000");

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        given(bankClient.payout(eq(BANK_CODE), eq(BANK_ACCOUNT_NUMBER),
                eq(new BigDecimal("10000.0000")), eq("KRW"), eq(KEY)))
                .willReturn(new PayoutResult(
                        "mock-payout-1", "COMPLETED", new BigDecimal("10000.0000"), "KRW",
                        new BigDecimal("500000.0000")));
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{\"any\":\"json\"}");

        TransferExecuteResponse response = service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID));

        // 응답: REMITTANCE COMPLETED + fee=50 + same-currency 모양 유지
        assertThat(response.transferType()).isEqualTo("REMITTANCE");
        assertThat(response.amount()).isEqualTo("10000.0000");
        assertThat(response.fee()).isEqualTo("50.0000");
        assertThat(response.currencyCode()).isEqualTo("KRW");
        assertThat(response.receiveAmount()).isEqualTo("10000.0000");
        assertThat(response.receiveCurrencyCode()).isEqualTo("KRW");
        assertThat(response.exchangeRate()).as("2단계 same-currency는 환율 null").isNull();
        assertThat(response.status()).isEqualTo("COMPLETED");

        // 송신자 잔액 차감: 1,000,000 → 989,950 (amount 10,000 + fee 50)
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("989950");

        // 핵심 verify — Writer 호출 (5개 인자 정확히)
        verify(remittanceAttemptWriter).record(
                eq(KEY), eq(SENDER_USER), eq(BANK_ACCOUNT_ID),
                eq(expectedTotalDeduct), eq(CurrencyType.KRW));

        // 순서 verify — 외부 호출 *직전* 흔적이라는 의도 회귀 보호.
        // Writer.record가 bankClient.payout보다 먼저 호출돼야 한다.
        InOrder inOrder = Mockito.inOrder(remittanceAttemptWriter, bankClient);
        inOrder.verify(remittanceAttemptWriter).record(any(), any(), any(), any(), any());
        inOrder.verify(bankClient).payout(any(), any(), any(), any(), any());

        // Transaction 1건 (REMITTANCE) + audit log 1건 (action="REMITTANCE")
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(TransactionType.REMITTANCE);
        assertThat(txCaptor.getValue().getBankAccountId()).isEqualTo(BANK_ACCOUNT_ID);
        assertThat(txCaptor.getValue().getReceiverWallet()).as("REMITTANCE는 외부 계좌 — 수신자 wallet 없음").isNull();
        assertThat(txCaptor.getValue().getFee()).isEqualByComparingTo("50.0000");

        ArgumentCaptor<TransactionAuditLog> logCaptor = ArgumentCaptor.forClass(TransactionAuditLog.class);
        verify(auditLogRepository, times(1)).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("REMITTANCE");
        assertThat(logCaptor.getValue().getUserPublicId()).isEqualTo(SENDER_USER);
        assertThat(logCaptor.getValue().getBeforeBalance()).isEqualByComparingTo("1000000");
        assertThat(logCaptor.getValue().getAfterBalance()).isEqualByComparingTo("989950");

        // REMITTANCE 경로는 분산 락/수신자 ensure 미진입.
        verifyNoInteractions(distributedLockHelper);
        verify(walletBalanceWriter, never()).ensureBalanceRow(any(), any());

        // 캐시 키는 (도메인, key, user, bank_account_pub_id) 4-튜플 스코프 명시 검증
        // — INTERNAL_TRANSFER와 도메인 prefix가 분리돼 cross-domain 역직렬화도 차단됨.
        ArgumentCaptor<String> remittanceCacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyCacheHelper).set(remittanceCacheKeyCaptor.capture(), anyString());
        assertThat(remittanceCacheKeyCaptor.getValue())
                .as("REMITTANCE 캐시 키는 4-튜플 스코프 형식: remittance:{key}:{user}:{bank_account_pub_id}")
                .contains("remittance", KEY, SENDER_USER, BANK_ACCOUNT_PUB_ID);

        // TX-PIN: REMITTANCE(외부 출금)도 자금 이동 전 PIN 게이트를 통과해야 한다(게이트는 유형 분기 이전 공용 코드).
        verify(transferPinGate).requireVerified(SENDER_USER);
    }

    @Test
    @DisplayName("REMITTANCE payout 실패(BANK4002 매핑 ACCOUNT4003): Writer.record는 *호출됨*, 본 tx Transaction/audit 저장 0회")
    void execute_REMITTANCE_payout실패_attempt는_남고_본_tx_rollback() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        // BankErrorMapper가 BANK4002 → ACCOUNT4003으로 매핑한 BusinessException을 던진 상태.
        given(bankClient.payout(any(), any(), any(), any(), any()))
                .willThrow(new BusinessException(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.INSUFFICIENT_LINKED_ACCOUNT_BALANCE);

        // 외부 호출 *전*에 record가 불렸으므로 실패해도 호출 사실은 남는다.
        // (실제 환경에선 REQUIRES_NEW로 remittance_attempts 행이 커밋돼 보존됨 — 단위 테스트는 mock이라
        //  실제 커밋 자체는 검증 불가. Writer 호출 사실만 검증. Testcontainers 백로그.)
        verify(remittanceAttemptWriter).record(
                eq(KEY), eq(SENDER_USER), eq(BANK_ACCOUNT_ID),
                eq(new BigDecimal("10050.0000")), eq(CurrencyType.KRW));

        // 본 tx의 Transaction/audit는 미저장 (외부 호출 예외 → 메인 @Transactional rollback)
        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
        // 잔액 차감 미적용 (예외가 차감 단계 전에 떨어짐)
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("REMITTANCE payout status != COMPLETED → COMMON5031: Writer는 호출됨, Transaction/audit 미저장")
    void execute_REMITTANCE_payout_status_COMPLETED아님_COMMON5031() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        // 정상 응답이지만 status가 비정상 — Service 방어 분기에서 COMMON5031로 매핑.
        given(bankClient.payout(any(), any(), any(), any(), any()))
                .willReturn(new PayoutResult(
                        "mock-payout-2", "FAILED", new BigDecimal("10000.0000"), "KRW",
                        new BigDecimal("0.0000")));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(remittanceAttemptWriter).record(any(), any(), any(), any(), any());
        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("WTX-09: payout status는 COMPLETED지만 응답 금액이 요청과 불일치 → COMMON5031, Writer 호출·Transaction/audit 미저장")
    void execute_REMITTANCE_payout_금액불일치_COMMON5031() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        // status는 COMPLETED지만 은행이 처리한 금액이 요청(10000)과 다름(9999) → 정합성 깨짐으로 보류.
        given(bankClient.payout(any(), any(), any(), any(), any()))
                .willReturn(new PayoutResult(
                        "mock-payout-x", "COMPLETED", new BigDecimal("9999.0000"), "KRW",
                        new BigDecimal("0.0000")));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(remittanceAttemptWriter).record(any(), any(), any(), any(), any()); // 흔적은 외부 호출 전 기록됨
        verify(transactionRepository, never()).save(any());                         // 잘못된 금액을 확정하지 않음
        verify(auditLogRepository, never()).save(any());
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("1000000");      // 잔액 차감 없음
    }

    @Test
    @DisplayName("REMITTANCE 잔액 부족(amount+fee > balance) → WALLET4002: 외부 호출 전 차단 — Writer/BankClient 미호출")
    void execute_REMITTANCE_잔액부족_WALLET4002_attempt미호출() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        // 10000 + 50 = 10050 필요한데 잔액 10000 → 부족
        WalletBalance senderBalance = balance(sender, new BigDecimal("10000"));
        BankAccount account = mockBankAccount(MOCK_TOKEN);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.INSUFFICIENT_BALANCE);

        // 잔액 검증이 외부 호출 *전* 차단 단계 — 흔적도 박지 않는다(의도 회귀 보호).
        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
        verifyNoInteractions(bankClient);
        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("REMITTANCE 계좌 부재(본인 + active 미매칭) → ACCOUNT4001: Writer 미호출")
    void execute_REMITTANCE_계좌부재_ACCOUNT4001_attempt미호출() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        // 계좌 검증 단계에서 차단 — 잔액·외부 호출·흔적 모두 미진입.
        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
        verifyNoInteractions(bankClient);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("REMITTANCE 계좌 미인증(mock_account_token=null) → ACCOUNT4006: Writer 미호출")
    void execute_REMITTANCE_계좌_미인증_ACCOUNT4006_attempt미호출() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        BankAccount account = mockBankAccount(null);  // 토큰 미발급

        stubCacheMiss();
        stubDbMiss();
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACCOUNT_PUB_ID, SENDER_USER))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> service.execute(
                SENDER_USER, KEY, remittanceRequest("10000.0000", BANK_ACCOUNT_PUB_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNVERIFIED_ACCOUNT);

        verify(remittanceAttemptWriter, never()).record(any(), any(), any(), any(), any());
        verifyNoInteractions(bankClient);
        verify(transactionRepository, never()).save(any());
    }

    // ===== WTX-02: 빈 Idempotency-Key 서비스단 가드(비-HTTP 경로) =====

    @ParameterizedTest(name = "[{index}] idempotencyKey=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("WTX-02: 빈/공백 Idempotency-Key → COMMON4001, rate-limit/캐시/DB/락 모두 미진입(side effect 전 fail-fast)")
    void execute_빈_idempotencyKey_COMMON4001(String blankKey) {
        assertThatThrownBy(() -> service.execute(SENDER_USER, blankKey, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        // 모든 side effect 전에 차단 — rate-limit 토큰 소모/캐시/DB/락 진입 0.
        // (rateLimitHelper는 @BeforeEach가 lenient 스텁해 두므로 never()로 "코드가 호출 안 함"만 검증.)
        verify(rateLimitHelper, never()).tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class));
        verifyNoInteractions(idempotencyCacheHelper, walletRepository,
                walletBalanceRepository, distributedLockHelper);
        verify(transactionRepository, never()).findByIdempotencyKey(anyString());
        verify(transactionRepository, never()).save(any());
    }

    // ===== 신규: P1 rate-limit =====

    @Test
    @DisplayName("Rate-limit 초과(tryAcquire=false) → TRANSFER4006, 실제 자금이동(지갑/락/저장) 미진입(WTX-04: dedup 뒤에서 차단)")
    void execute_rateLimit_초과_TRANSFER4006() {
        // WTX-04: rate-limit은 이제 Layer1(캐시)·Layer2(DB) dedup *뒤*에 위치한다. 신규 키라 캐시/DB는 miss로
        // 거친 뒤 rate-limit에서 막힌다(둘 다 stub 필요).
        stubCacheMiss();
        stubDbMiss();
        // 기본 @BeforeEach가 true로 설정한 stub을 false로 덮어쓴다(이 케이스 한정).
        given(rateLimitHelper.tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class)))
                .willReturn(false);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.RATE_LIMIT_EXCEEDED);

        // dedup(캐시/DB)은 거치되, 실제 자금 이동(지갑/락/저장)은 미진입.
        verifyNoInteractions(walletRepository, walletBalanceRepository, distributedLockHelper);
        verify(transactionRepository, never()).save(any());
    }

    // ===== 신규: P0-② rebuildFromPrior cross-user/scope 검증 =====

    @Test
    @DisplayName("Layer 2 hit인데 prior 거래의 sender가 요청자와 다름(cross-user) → WALLET4001로 모호 매핑, 응답 노출 차단")
    void execute_Layer2_crossUser_blocked() {
        Wallet otherSender = wallet(999L, "other-user-uuid");
        Transaction prior = priorTransaction(otherSender);

        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));

        // 다른 사용자(SENDER_USER)가 같은 idempotency_key로 요청 → 검증 실패로 INTERNAL_TRANSFER 사유 매핑.
        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        // 검증 실패라 캐시 set·executeInTransaction 진입 0.
        verify(idempotencyCacheHelper, never()).set(anyString(), anyString());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Layer 2 hit인데 prior 거래의 receiver가 요청 receiver와 다름(cross-scope) → WALLET4001로 모호 매핑")
    void execute_Layer2_crossReceiverScope_blocked() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        // prior의 receiver는 RECEIVER_USER인데, 요청 receiver는 다른 사용자.
        Transaction prior = priorTransaction(sender);

        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));

        TransferExecuteRequest req = new TransferExecuteRequest(
                "INTERNAL_TRANSFER", "10000.0000", "KRW", "KRW", "메모",
                "different-receiver-uuid", null);
        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        verify(idempotencyCacheHelper, never()).set(anyString(), anyString());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Layer 2 REMITTANCE: prior 거래의 bank_account가 요청과 다름(cross-account) → ACCOUNT4001로 모호 매핑")
    void execute_Layer2_REMITTANCE_crossAccount_blocked() {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        BankAccount priorAccount = mockBankAccount(MOCK_TOKEN); // public_id = BANK_ACCOUNT_PUB_ID
        Transaction prior = Transaction.builder()
                .publicId("prior-remit-public-id")
                .wallet(sender)
                .type(TransactionType.REMITTANCE)
                .amount(new BigDecimal("10000"))
                .currencyCode(CurrencyType.KRW)
                .fee(new BigDecimal("50"))
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(KEY)
                .bankAccountId(BANK_ACCOUNT_ID)
                .receiveAmount(new BigDecimal("10000"))
                .receiveCurrencyCode(CurrencyType.KRW)
                .build();
        ReflectionTestUtils.setField(prior, "id", 51L);
        ReflectionTestUtils.setField(prior, "createdAt", FIXED);

        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));
        given(bankAccountRepository.findById(BANK_ACCOUNT_ID)).willReturn(Optional.of(priorAccount));

        // priorAccount.publicId == BANK_ACCOUNT_PUB_ID이지만, 요청은 다른 계좌 사용 → scope mismatch
        TransferExecuteRequest req = remittanceRequest("10000.0000", "different-account-uuid");

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        // 검증 실패 → 캐시 set·신규 거래 INSERT 모두 미진입.
        verify(idempotencyCacheHelper, never()).set(anyString(), anyString());
        verify(transactionRepository, never()).save(any());
    }

    // ===== TX-PIN: 송금 PIN 서버측 게이트 =====

    @Test
    @DisplayName("TX-PIN: 게이트가 TRANSFER4010(미검증) throw → 자금 이동 0 (락/거래/감사/지갑조회 미진입)")
    void execute_PIN미검증_TRANSFER4010_자금이동없음() {
        stubCacheMiss();
        stubDbMiss();
        willThrow(new BusinessException(TransferErrorCode.PIN_VERIFICATION_REQUIRED))
                .given(transferPinGate).requireVerified(SENDER_USER);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_VERIFICATION_REQUIRED);

        // 게이트는 executeWithRetry(지갑 조회·락·자금 이동) 앞 — 어떤 side effect도 일어나지 않는다.
        verifyNoInteractions(distributedLockHelper, walletBalanceRepository, bankClient,
                remittanceAttemptWriter);
        verify(walletRepository, never()).findByUserPublicId(anyString());
        verify(transactionRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("TX-PIN: 게이트가 TRANSFER4009(PIN 미설정) throw → 자금 이동 0")
    void execute_PIN미설정_TRANSFER4009_자금이동없음() {
        stubCacheMiss();
        stubDbMiss();
        willThrow(new BusinessException(TransferErrorCode.PIN_NOT_SET))
                .given(transferPinGate).requireVerified(SENDER_USER);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.PIN_NOT_SET);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(distributedLockHelper);
    }

    @Test
    @DisplayName("TX-PIN: rate-limit 초과(TRANSFER4006)면 게이트 미도달 — 검증 마커를 헛되이 소모하지 않는다(게이트는 rate-limit 뒤)")
    void execute_rateLimit초과시_게이트_미도달() {
        stubCacheMiss();
        stubDbMiss();
        given(rateLimitHelper.tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class)))
                .willReturn(false);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, request("10000.0000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.RATE_LIMIT_EXCEEDED);

        verifyNoInteractions(transferPinGate); // 게이트 미도달 → 마커 미소비(rate-limit 막힘 시 재검증 불필요)
    }

    @Test
    @DisplayName("TX-PIN: executePreAuthorized(스케줄러)는 PIN 게이트를 건너뛰고 자금을 이동한다(standing order)")
    void executePreAuthorized_게이트_우회_정상송금() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);
        RLock lock = lock();

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock);
        stubBalanceLocks(sender, receiver, senderBalance, receiverBalance);
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse response =
                service.executePreAuthorized(SENDER_USER, KEY, request("10000.0000"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("990000");
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo("10000");
        // 핵심: 사전 인가 경로라 PIN 게이트를 호출하지 않는다(설정 시 1회 인가한 standing order).
        verifyNoInteractions(transferPinGate);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        // schedule-pin-3: 단, rate-limit은 수동 송금과 동일하게 공유한다(스케줄러 폭주 backstop).
        verify(rateLimitHelper).tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("TX-PIN: executePreAuthorized도 멱등 캐시 hit는 그대로 재반환(게이트 무관, 이중 송금 방지)")
    void executePreAuthorized_캐시hit_재반환() throws Exception {
        TransferExecuteResponse cached = stubCachedResponse();
        given(idempotencyCacheHelper.get(anyString())).willReturn(Optional.of("{\"cached\":\"json\"}"));
        given(objectMapper.readValue(anyString(), eq(TransferExecuteResponse.class))).willReturn(cached);

        TransferExecuteResponse result =
                service.executePreAuthorized(SENDER_USER, KEY, request("10000.0000"));

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(transferPinGate, distributedLockHelper);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("TX-PIN: 락 경합 재시도(PessimisticLockingFailureException)가 일어나도 PIN 게이트는 1회만 — 마커는 재시도 바깥에서 1회 소비")
    void execute_락경합재시도시_PIN게이트_정확히1회() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Wallet receiver = wallet(RECEIVER_WALLET_ID, RECEIVER_USER);
        WalletBalance senderBalance = balance(sender, new BigDecimal("1000000"));
        WalletBalance receiverBalance = balance(receiver, BigDecimal.ZERO);

        stubCacheMiss();
        stubDbMiss();
        stubWalletLookups(sender, receiver);
        stubLockAcquired(lock());
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(walletRepository.findById(RECEIVER_WALLET_ID)).willReturn(Optional.of(receiver));
        // 1차 시도: 송신자 잔액 FOR UPDATE에서 락 경합 → 트랜잭션 롤백 → executeWithRetry가 재시도. 2차: 정상.
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .willReturn(Optional.of(senderBalance));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(receiver, CurrencyType.KRW))
                .willReturn(Optional.of(receiverBalance));
        stubTransactionSaveAndAuditLog();
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse response = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        // 핵심: 게이트는 executeWithRetry *바깥*(rate-limit 뒤·재시도 루프 앞)이라, 락 경합으로 회차가 2번
        // 돌아도 마커는 정확히 1회만 소비된다 — 재시도가 PIN 재검증을 요구하지 않는다.
        verify(transferPinGate, times(1)).requireVerified(SENDER_USER);
        // 2차 시도에서 1회만 차감(중복 차감 없음).
        assertThat(senderBalance.getBalance()).isEqualByComparingTo("990000");
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo("10000");
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    // ===== helpers — stub blocks =====

    private void stubCacheMiss() {
        // 캐시 키는 도메인·user·scope로 스코프된 형태(remittance:.../internal_transfer:...)라
        // 정확한 키 매칭 대신 anyString()으로 통과 — 키 스코프 정확성은 별도 케이스에서 검증.
        // lenient: 입력 검증 단계에서 차단되는 케이스(미지원 통화/유형, REMITTANCE 필수 필드 누락 등)는
        // 캐시 조회까지 도달하지 않으므로 strict stub로 두면 UnnecessaryStubbingException이 난다.
        Mockito.lenient().when(idempotencyCacheHelper.get(anyString())).thenReturn(Optional.empty());
    }

    private void stubDbMiss() {
        Mockito.lenient().when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
    }

    private void stubWalletLookups(Wallet sender, Wallet receiver) {
        given(walletRepository.findByUserPublicId(SENDER_USER)).willReturn(Optional.of(sender));
        given(walletRepository.findByUserPublicId(RECEIVER_USER)).willReturn(Optional.of(receiver));
    }

    private void stubLockAcquired(RLock lock) {
        given(distributedLockHelper.tryLockTwoWallets(SENDER_WALLET_ID, RECEIVER_WALLET_ID))
                .willReturn(lock);
    }

    private void stubBalanceLocks(Wallet sender, Wallet receiver,
                                  WalletBalance senderBalance, WalletBalance receiverBalance) {
        given(walletRepository.findById(SENDER_WALLET_ID)).willReturn(Optional.of(sender));
        given(walletRepository.findById(RECEIVER_WALLET_ID)).willReturn(Optional.of(receiver));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(sender, CurrencyType.KRW))
                .willReturn(Optional.of(senderBalance));
        given(walletBalanceRepository.findForUpdateByWalletAndCurrency(receiver, CurrencyType.KRW))
                .willReturn(Optional.of(receiverBalance));
    }

    private void stubTransactionSaveAndAuditLog() {
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L);
            ReflectionTestUtils.setField(t, "createdAt", FIXED);
            return t;
        });
        given(auditLogRepository.save(any(TransactionAuditLog.class))).willAnswer(inv -> inv.getArgument(0));
    }

    // ===== helpers — fixtures =====

    private TransferExecuteRequest request(String amount) {
        return new TransferExecuteRequest(
                "INTERNAL_TRANSFER", amount, "KRW", "KRW", "테스트", RECEIVER_USER, null);
    }

    private Wallet wallet(long id, String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId("wallet-pub-" + id)
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(w, "id", id);
        return w;
    }

    /** WTX-05 테스트용 SUSPENDED(동결) 지갑. */
    private Wallet suspendedWallet(long id, String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId("wallet-pub-" + id)
                .userPublicId(userPublicId)
                .status(WalletStatus.SUSPENDED)
                .build();
        ReflectionTestUtils.setField(w, "id", id);
        return w;
    }

    private WalletBalance balance(Wallet wallet, BigDecimal initial) {
        return WalletBalance.builder()
                .wallet(wallet)
                .currencyCode(CurrencyType.KRW)
                .balance(initial)
                .build();
    }

    private Transaction priorTransaction(Wallet sender) {
        Transaction t = Transaction.builder()
                .publicId("prior-tx-public-id")
                .wallet(sender)
                .type(TransactionType.INTERNAL_TRANSFER)
                .amount(new BigDecimal("10000"))
                .currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(KEY)
                .receiverWallet(wallet(RECEIVER_WALLET_ID, RECEIVER_USER))
                .receiveAmount(new BigDecimal("10000"))
                .receiveCurrencyCode(CurrencyType.KRW)
                .build();
        ReflectionTestUtils.setField(t, "id", 50L);
        ReflectionTestUtils.setField(t, "createdAt", FIXED);
        return t;
    }

    private TransferExecuteResponse stubCachedResponse() {
        return new TransferExecuteResponse(
                "cached-public-id", "INTERNAL_TRANSFER", "10000.0000", "KRW", "0.0000",
                null, "10000.0000", "KRW", "COMPLETED", "2026-06-01T04:15:30Z");
    }

    private RLock lock() {
        RLock lock = org.mockito.Mockito.mock(RLock.class);
        given(lock.isHeldByCurrentThread()).willReturn(true);
        return lock;
    }

    // ----- REMITTANCE 전용 fixtures -----

    private TransferExecuteRequest remittanceRequest(String amount, String bankAccountPublicId) {
        // receiverPublicId는 DTO @NotBlank로 강제되므로 dummy 값(REMITTANCE 분기에서 미사용).
        return new TransferExecuteRequest(
                "REMITTANCE", amount, "KRW", "KRW", "현금화", RECEIVER_USER, bankAccountPublicId);
    }

    /**
     * BankAccount + Bank를 Mockito mock으로 구성한다. Entity 빌더로 만들 수도 있지만 Bank 마스터
     * 데이터까지 채우는 비용이 본 단위 테스트 범위와 무관하므로 mock이 간결.
     */
    private BankAccount mockBankAccount(String mockAccountToken) {
        BankAccount account = Mockito.mock(BankAccount.class);
        Bank bank = Mockito.mock(Bank.class);
        // 모든 getter를 lenient로 둔다 — REMITTANCE 실행 흐름은 토큰 확인까지 도달하지만,
        // Layer 2 rebuildFromPrior 차단 시나리오(cross-account 등)는 그 단계 전에 막힌다.
        // strict로 두면 후자 케이스에서 getMockAccountToken stub이 UnnecessaryStubbingException 유발.
        Mockito.lenient().when(account.getId()).thenReturn(BANK_ACCOUNT_ID);
        Mockito.lenient().when(account.getPublicId()).thenReturn(BANK_ACCOUNT_PUB_ID);
        Mockito.lenient().when(account.getAccountNumber()).thenReturn(BANK_ACCOUNT_NUMBER);
        Mockito.lenient().when(account.getMockAccountToken()).thenReturn(mockAccountToken);
        Mockito.lenient().when(account.getBank()).thenReturn(bank);
        Mockito.lenient().when(bank.getCode()).thenReturn(BANK_CODE);
        return account;
    }
}
