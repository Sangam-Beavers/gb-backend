package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.domain.wallet.repository.WalletBalanceRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.dto.WithdrawalResult;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.WalletStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 충전 잔액 증액 통합 테스트(@SpringBootTest + H2). 실제 트랜잭션 커밋을 거쳐 잔액 증액 / audit_log 적재 /
 * 멱등성(부수효과 없는 첫 응답 재반환)을 검증한다.
 *
 * <p>외부 클라이언트는 dev/stage 프로파일에서만 등록되므로 {@code @MockitoBean}으로 가린다. 테스트를
 * {@code @Transactional}로 두지 <b>않는다</b> — doCharge가 자기 트랜잭션을 열고 커밋하는 실제 동작을
 * 그대로 검증하기 위함이며, 대신 {@code @BeforeEach}에서 FK 순서로 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChargeServiceIntegrationTest {

    @MockitoBean private BankClient bankClient;
    @MockitoBean private MemberClient memberClient;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단 — 컨텍스트 로딩용).
    @MockitoBean private JwtDecoder jwtDecoder;
    // 충전 멱등성 Layer 1(Redis 캐시)이 RedissonClient를 쓴다. 테스트엔 Redis가 없으므로 @MockitoBean으로
    // 가려 실제 연결을 막는다. 아래 setUp에서 getBucket → no-op 버킷(get()=null)을 반환하도록 stub해
    // 캐시는 항상 miss(=Layer 2 DB가 멱등성을 담당)로 동작시킨다.
    @MockitoBean private RedissonClient redissonClient;

    @Autowired private ChargeService chargeService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;
    @Autowired private BankRepository bankRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    // 스파이: 대부분의 테스트는 실제 동작에 위임하지만, race 테스트에서 멱등성 선검사만 빈 결과로 stub해
    // 실제 UNIQUE 위반(DataIntegrityViolationException) → readPrior 경로를 단일 스레드로 결정적으로 재현한다.
    @MockitoSpyBean private TransactionRepository transactionRepository;
    @Autowired private TransactionAuditLogRepository auditLogRepository;

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String IP = "127.0.0.1";

    private Wallet wallet;
    private BankAccount account;

    @BeforeEach
    void setUp() {
        clearAll();

        Bank bank = bankRepository.save(Bank.builder()
                .code("004").name("KB국민은행").country("KR").isDomestic(true).isActive(true).build());
        wallet = walletRepository.save(Wallet.builder()
                .publicId(UUID.randomUUID().toString()).userPublicId(USER).status(WalletStatus.ACTIVE).build());
        account = bankAccountRepository.save(BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(USER)
                .bank(bank)
                .accountNumber("1234567890")
                .mockAccountToken("tok-int")
                .isVirtual(false).isPrimary(true).isActive(true)
                .build());

        // Mock 은행은 항상 요청 금액으로 COMPLETED 반환.
        given(bankClient.withdraw(anyString(), any(BigDecimal.class), anyString(), anyString()))
                .willAnswer(inv -> new WithdrawalResult(
                        "mock-tx", "COMPLETED", inv.getArgument(1), "KRW", BigDecimal.ZERO));

        // 멱등성 Layer 1 캐시는 항상 miss(no-op 버킷). get()=null이라 캐시 hit 없이 DB Layer 2가 멱등성을 담당.
        @SuppressWarnings("rawtypes")
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(anyString())).willReturn(bucket);
    }

    /**
     * 이 테스트는 {@code @Transactional}이 아니라 실제로 커밋한다(자기 트랜잭션·UNIQUE 위반을 진짜로 검증하기
     * 위함). 따라서 공유 인메모리 H2({@code DB_CLOSE_DELAY=-1})에 데이터가 남아 다른 테스트 클래스의 고정
     * UUID/은행코드와 충돌할 수 있다. 매 메서드 전·후로 정리해 누수를 차단한다(@AfterEach는 실패 시에도 실행됨).
     */
    @AfterEach
    void tearDown() {
        clearAll();
    }

    /** FK 순서로 정리(자식 → 부모). bank_account_id는 원시 Long이라 transactions에 FK 없음. */
    private void clearAll() {
        auditLogRepository.deleteAll();
        transactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
        bankAccountRepository.deleteAll();
        walletRepository.deleteAll();
        bankRepository.deleteAll();
    }

    @Test
    @DisplayName("충전 성공 시 기존 KRW 잔액이 증액되고 audit_log가 before/after로 쌓인다")
    void 충전_성공시_지갑_KRW가_증액되고_audit_log가_쌓인다() {
        walletBalanceRepository.save(WalletBalance.builder()
                .wallet(wallet).currencyCode(CurrencyType.KRW).balance(new BigDecimal("1000000")).build());

        ChargeResponse response = chargeService.charge(
                USER, account.getPublicId(), "key-1", request("500000"), IP);

        assertThat(response.getWalletBalance()).isEqualTo("1500000.0000");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getAccountPublicId()).isEqualTo(account.getPublicId());

        List<WalletBalance> balances = walletBalanceRepository.findByWallet(wallet);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getBalance()).isEqualByComparingTo("1500000");

        List<Transaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getType()).isEqualTo(TransactionType.CHARGE);
        assertThat(txs.get(0).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(txs.get(0).getBankAccountId()).isEqualTo(account.getId());

        List<TransactionAuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getBeforeBalance()).isEqualByComparingTo("1000000");
        assertThat(logs.get(0).getAfterBalance()).isEqualByComparingTo("1500000");
        assertThat(logs.get(0).getAction()).isEqualTo("CHARGE");
        assertThat(logs.get(0).getUserPublicId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("첫 KRW 충전이면 balance 행이 새로 생성된다(§5-5)")
    void 첫_KRW_충전이면_balance_row가_새로_생성된다() {
        assertThat(walletBalanceRepository.findByWallet(wallet)).isEmpty();

        ChargeResponse response = chargeService.charge(
                USER, account.getPublicId(), "key-2", request("300000"), IP);

        assertThat(response.getWalletBalance()).isEqualTo("300000.0000");

        List<WalletBalance> balances = walletBalanceRepository.findByWallet(wallet);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getCurrencyCode()).isEqualTo(CurrencyType.KRW);
        assertThat(balances.get(0).getBalance()).isEqualByComparingTo("300000");

        List<TransactionAuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getBeforeBalance()).isEqualByComparingTo("0");
        assertThat(logs.get(0).getAfterBalance()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("동일 Idempotency-Key로 두 번 호출하면 잔액 1회만 증액, 둘째는 첫 응답 재반환(부수효과 없음)")
    void 동일_idempotencyKey로_두번_호출하면_부수효과없이_첫응답_재반환() {
        String key = "key-dup";

        ChargeResponse first = chargeService.charge(USER, account.getPublicId(), key, request("400000"), IP);
        ChargeResponse second = chargeService.charge(USER, account.getPublicId(), key, request("400000"), IP);

        // Mock 출금은 1회만, 잔액은 1회만 증액
        verify(bankClient, times(1)).withdraw(anyString(), any(BigDecimal.class), anyString(), anyString());
        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(auditLogRepository.findAll()).hasSize(1);
        assertThat(walletBalanceRepository.findByWallet(wallet).get(0).getBalance())
                .isEqualByComparingTo("400000");

        // 두 응답은 동일(첫 결과 재반환)
        assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
        assertThat(second.getWalletBalance()).isEqualTo(first.getWalletBalance());
        assertThat(second.getWalletBalance()).isEqualTo("400000.0000");
    }

    @Test
    @DisplayName("다른 사용자가 같은 Idempotency-Key로 호출하면 ACCOUNT4001(첫 결과 탈취 차단)")
    void 다른_사용자_같은키면_ACCOUNT4001() {
        String key = "key-shared";
        chargeService.charge(USER, account.getPublicId(), key, request("100000"), IP);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        chargeService.charge("22222222-2222-2222-2222-222222222222",
                                account.getPublicId(), key, request("100000"), IP))
                .isInstanceOf(com.gb.common.exception.BusinessException.class)
                .extracting(ex -> ((com.gb.common.exception.BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.wallet.global.exception.code.AccountErrorCode.ACCOUNT_NOT_FOUND);

        // 첫 사용자 잔액은 1회 증액 그대로
        assertThat(walletBalanceRepository.findByWallet(wallet).get(0).getBalance())
                .isEqualByComparingTo("100000");
        verify(bankClient, times(1)).withdraw(anyString(), any(BigDecimal.class), anyString(), anyString());
        verify(memberClient, never()).getMember(anyString());
    }

    @Test
    @DisplayName("race(DataIntegrityViolationException) 경로: 실제 UNIQUE 위반 → 별도 트랜잭션 readPrior로 첫 응답 재구성(§5-1)")
    void race_실제_UNIQUE위반시_readPrior로_첫응답_재구성() {
        String key = "race-key";

        // 이미 처리된 첫 거래 + 감사 로그를 커밋 상태로 심는다(첫 응답의 SSOT).
        Transaction prior = transactionRepository.save(Transaction.builder()
                .publicId("prior-pid")
                .wallet(wallet)
                .type(TransactionType.CHARGE)
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .fee(BigDecimal.ZERO)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(key)
                .bankAccountId(account.getId())
                .build());
        auditLogRepository.save(TransactionAuditLog.builder()
                .transaction(prior)
                .userPublicId(USER)
                .action("CHARGE")
                .amount(new BigDecimal("500000"))
                .currencyCode(CurrencyType.KRW)
                .beforeBalance(new BigDecimal("1000000"))
                .afterBalance(new BigDecimal("1500000")) // 당시 충전 후 잔액
                .status(TransactionStatus.COMPLETED)
                .build());

        // race 시뮬레이션(단일 스레드, 결정적): findByIdempotencyKey만 스파이로 통제한다.
        //  - 1번째 호출(doCharge 멱등성 선검사): 빈 결과 → 경쟁 창을 모사해 doCharge가 끝까지 진행.
        //    이어 실제 transactionRepository.save(tx)가 위에서 커밋해둔 같은 key와 충돌 →
        //    진짜 DataIntegrityViolationException 발생(IDENTITY라 save 시점 즉시 INSERT).
        //  - 2번째 호출(readPrior 재조회): 먼저 커밋된 첫 거래를 반환.
        // (doCallRealMethod는 인터페이스(Spring Data) 스파이에서 호출할 실제 본문이 없어 부적합 → 명시적 반환을 쓴다.)
        doReturn(Optional.empty())
                .doReturn(Optional.of(prior))
                .when(transactionRepository).findByIdempotencyKey(key);

        ChargeResponse response = chargeService.charge(
                USER, account.getPublicId(), key, request("700000"), IP);

        // 첫 응답이 재구성됨(새 거래 아님): publicId/walletBalance가 심어둔 첫 거래·감사 로그와 일치
        assertThat(response.getPublicId()).isEqualTo("prior-pid");
        assertThat(response.getAmount()).isEqualTo("500000.0000");
        assertThat(response.getWalletBalance()).as("현재 잔액이 아닌 당시 after_balance").isEqualTo("1500000.0000");

        // 부수효과 없음: 거래·감사 로그는 첫 1건만(증액 롤백). 단, 잔액 행은 WalletBalanceWriter가 별도
        // 트랜잭션(REQUIRES_NEW)으로 0원 행을 보장하므로 남을 수 있고, 충전 증액은 롤백되므로 잔액은 0이어야 한다.
        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(auditLogRepository.findAll()).hasSize(1);
        assertThat(walletBalanceRepository.findByWallet(wallet))
                .allSatisfy(b -> assertThat(b.getBalance()).isEqualByComparingTo("0"));

        // doCharge 본문이 실제로 실행됐음(선검사로 빠지지 않음)을 withdraw 호출로 증명.
        verify(bankClient, times(1)).withdraw(anyString(), any(BigDecimal.class), anyString(), anyString());
        // 멱등성 키 조회는 2회: doCharge 선검사 + readPrior 재조회.
        verify(transactionRepository, times(2)).findByIdempotencyKey(key);
    }

    private ChargeRequest request(String amount) {
        ChargeRequest r = new ChargeRequest();
        ReflectionTestUtils.setField(r, "amount", new BigDecimal(amount));
        return r;
    }
}