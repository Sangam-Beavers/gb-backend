package com.gb.wallet.domain.transaction.service.impl;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.domain.wallet.service.WalletBalanceWriter;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.IdempotencyCacheHelper;
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
import org.redisson.api.RLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private MemberClient memberClient;
    @Mock private BankClient bankClient;
    @Mock private DistributedLockHelper distributedLockHelper;
    @Mock private IdempotencyCacheHelper idempotencyCacheHelper;
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

        // 캐시 저장 + 락 해제
        verify(idempotencyCacheHelper).set(eq(KEY), anyString());
        verify(lock).unlock();
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
    @DisplayName("1단계 명시 차단: REMITTANCE는 ALLOWED엔 있지만 1단계에선 차단 → TRANSFER4003")
    void execute_REMITTANCE_1단계차단_TRANSFER4003() {
        stubCacheMiss();
        stubDbMiss();

        TransferExecuteRequest req = new TransferExecuteRequest(
                "REMITTANCE", "10000.0000", "KRW", "KRW", null, RECEIVER_USER, null);

        assertThatThrownBy(() -> service.execute(SENDER_USER, KEY, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE);
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
        given(idempotencyCacheHelper.get(KEY)).willReturn(Optional.of("{\"cached\":\"json\"}"));
        given(objectMapper.readValue(anyString(), eq(TransferExecuteResponse.class))).willReturn(cached);

        TransferExecuteResponse result = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(walletRepository, walletBalanceRepository, distributedLockHelper,
                auditLogRepository, bankClient, memberClient, bankAccountRepository);
        verify(transactionRepository, never()).findByIdempotencyKey(anyString());
        // replay 경로는 executeInTransaction 미진입 — ensure도 호출되면 안 된다.
        verify(walletBalanceWriter, never()).ensureBalanceRow(any(), any());
    }

    @Test
    @DisplayName("Layer 2(Redis miss + DB hit): DB 거래 1건 그대로 반환 + 캐시 채움, 락/외부 호출 0회")
    void execute_Layer2_dbHit() throws Exception {
        Wallet sender = wallet(SENDER_WALLET_ID, SENDER_USER);
        Transaction prior = priorTransaction(sender);

        given(idempotencyCacheHelper.get(KEY)).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(prior));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        TransferExecuteResponse result = service.execute(SENDER_USER, KEY, request("10000.0000"));

        assertThat(result.amount()).isEqualTo("10000.0000");
        assertThat(result.status()).isEqualTo("COMPLETED");
        // 락/외부 호출 미진입
        verifyNoInteractions(distributedLockHelper, walletBalanceRepository, auditLogRepository);
        verify(walletRepository, never()).findByUserPublicId(anyString());
        // 캐시 채움(다음 동일 키 요청은 Layer 1로 처리)
        verify(idempotencyCacheHelper).set(eq(KEY), anyString());
        // replay 경로는 executeInTransaction 미진입 — ensure도 호출되면 안 된다.
        verify(walletBalanceWriter, never()).ensureBalanceRow(any(), any());
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

    // ===== helpers — stub blocks =====

    private void stubCacheMiss() {
        given(idempotencyCacheHelper.get(KEY)).willReturn(Optional.empty());
    }

    private void stubDbMiss() {
        given(transactionRepository.findByIdempotencyKey(KEY)).willReturn(Optional.empty());
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
}
