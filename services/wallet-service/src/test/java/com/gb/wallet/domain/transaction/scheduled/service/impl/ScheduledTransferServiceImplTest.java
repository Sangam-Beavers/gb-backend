package com.gb.wallet.domain.transaction.scheduled.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.scheduled.dto.request.CreateScheduledTransferRequest;
import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.domain.transaction.scheduled.repository.ScheduledTransferRepository;
import com.gb.wallet.domain.transaction.scheduled.service.NextRunDateCalculator;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.TransferFrequency;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ScheduledTransferServiceImpl#create} 단위 테스트. enum 분기·검증·INSERT 매핑·snapshot을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTransferServiceImplTest {

    @Mock private ScheduledTransferRepository scheduledTransferRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private MemberClient memberClient;
    @Mock private NextRunDateCalculator nextRunDateCalculator;
    @InjectMocks private ScheduledTransferServiceImpl service;

    private static final String SENDER = "sender-uuid";
    private static final String RECEIVER = "receiver-uuid";
    private static final String BANK_ACC_PUB_ID = "bank-acc-pub-7g8h";
    private static final long BANK_ACC_ID = 99L;
    private static final LocalDate FIXED_NEXT = LocalDate.of(2026, 6, 25);

    @Test
    @DisplayName("create REMITTANCE 정상: 본인 활성 계좌 + 토큰 있음 → ScheduledTransfer INSERT + holderName snapshot")
    void create_REMITTANCE_정상() {
        BankAccount account = bankAccount(BANK_ACC_ID, "NGUYEN VAN A");
        ReflectionTestUtils.setField(account, "mockAccountToken", "tok-abc");
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER))
                .willReturn(Optional.of(account));
        Mockito.lenient().when(nextRunDateCalculator.calculate(any(), anyInt())).thenReturn(FIXED_NEXT);
        given(scheduledTransferRepository.save(any(ScheduledTransfer.class)))
                .willAnswer(inv -> stamp(inv.getArgument(0)));

        var resp = service.create(SENDER, remittanceReq(TransferFrequency.MONTHLY.name(), 25));

        assertThat(resp.transferType()).isEqualTo("REMITTANCE");
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.nextRunDate()).isEqualTo("2026-06-25");
        assertThat(resp.scheduleDay()).isEqualTo(25);
    }

    @Test
    @DisplayName("create INTERNAL_TRANSFER 정상: 수신자 wallet 존재 + MemberClient로 receiver_name snapshot")
    void create_INTERNAL_정상() {
        given(walletRepository.findByUserPublicId(RECEIVER))
                .willReturn(Optional.of(wallet(10L, RECEIVER)));
        given(memberClient.getMember(RECEIVER))
                .willReturn(new MemberInfo(RECEIVER, "r@example.com", "Nguyen Thi Linh", "Linh", "VN", true));
        Mockito.lenient().when(nextRunDateCalculator.calculate(any(), anyInt())).thenReturn(FIXED_NEXT);
        given(scheduledTransferRepository.save(any(ScheduledTransfer.class)))
                .willAnswer(inv -> stamp(inv.getArgument(0)));

        var resp = service.create(SENDER, internalReq(TransferFrequency.WEEKLY.name(), 3));

        assertThat(resp.transferType()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(resp.frequency()).isEqualTo("WEEKLY");
    }

    @Test
    @DisplayName("create: schedule_day 범위 초과(WEEKLY=8) → COMMON4221(422)")
    void create_scheduleDay_범위초과_COMMON4221() {
        var req = remittanceReq(TransferFrequency.WEEKLY.name(), 8);
        assertThatThrownBy(() -> service.create(SENDER, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("create: currency != receive_currency → COMMON4221(422) (1·2단계 same-currency 강제)")
    void create_currency_mismatch_COMMON4221() {
        // same-currency 검증은 도메인 분기 *전*에 일어나므로 bankAccountRepository stub 불필요.
        var req = new CreateScheduledTransferRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "10000.0000", "KRW", "VND", "MONTHLY", 25, null);

        assertThatThrownBy(() -> service.create(SENDER, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("create REMITTANCE: 본인 활성 계좌 미매칭 → ACCOUNT4001")
    void create_REMITTANCE_계좌없음_ACCOUNT4001() {
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue(BANK_ACC_PUB_ID, SENDER))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(SENDER, remittanceReq("MONTHLY", 25)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("create INTERNAL: 자기 자신 송금 → TRANSFER4004")
    void create_INTERNAL_self_TRANSFER4004() {
        var req = new CreateScheduledTransferRequest(
                "INTERNAL_TRANSFER", SENDER, null, "10000.0000", "KRW", "KRW", "MONTHLY", 25, null);

        assertThatThrownBy(() -> service.create(SENDER, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
    }

    @Test
    @DisplayName("create INTERNAL: 수신자 wallet 부재 → WALLET4001")
    void create_INTERNAL_수신자_wallet_없음_WALLET4001() {
        given(walletRepository.findByUserPublicId(RECEIVER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(SENDER, internalReq("MONTHLY", 25)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("create: 미지원 통화(EUR) → TRANSFER4002")
    void create_미지원통화_TRANSFER4002() {
        var req = new CreateScheduledTransferRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "10000.0000", "EUR", "EUR", "MONTHLY", 25, null);

        assertThatThrownBy(() -> service.create(SENDER, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.UNSUPPORTED_CURRENCY);
    }

    @Test
    @DisplayName("create: amount=0 → COMMON4001 (정규식 우회/프로그램 경로 방어용 signum 검증)")
    void create_amount0_COMMON4001() {
        // @Pattern으로도 0이 차단되지만(DTO 정규식), 컨트롤러를 거치지 않는 프로그램 경로/
        // 정규식 우회 방어를 위해 service 단에 추가한 signum 검증이 동작하는지 확인.
        // 검증 순서상 (3-2) amount 양수 검증이 (4) 도메인 대상 조회보다 먼저 fire 되므로
        // BankAccountRepository 등 추가 mock 불필요(Mockito strict — 불필요 stub 금지).
        var req = new CreateScheduledTransferRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "0", "KRW", "KRW", "MONTHLY", 25, null);

        assertThatThrownBy(() -> service.create(SENDER, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(com.gb.common.exception.CommonErrorCode.INVALID_REQUEST);
    }

    // ===== helpers =====

    private CreateScheduledTransferRequest remittanceReq(String frequency, int scheduleDay) {
        return new CreateScheduledTransferRequest(
                "REMITTANCE", null, BANK_ACC_PUB_ID, "500000.0000", "KRW", "KRW",
                frequency, scheduleDay, "매달 생활비");
    }

    private CreateScheduledTransferRequest internalReq(String frequency, int scheduleDay) {
        return new CreateScheduledTransferRequest(
                "INTERNAL_TRANSFER", RECEIVER, null, "10000.0000", "KRW", "KRW",
                frequency, scheduleDay, null);
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

    private BankAccount bankAccount(long id, String holderName) {
        Bank bank = Bank.builder()
                .code("020").name("Quokka Bank").country("VN").isDomestic(false).isActive(true).build();
        BankAccount a = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(SENDER)
                .bank(bank)
                .accountNumber("1002345678901")
                .holderName(holderName)
                .isVirtual(false).isPrimary(false).isActive(true)
                .build();
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    private ScheduledTransfer stamp(ScheduledTransfer s) {
        ReflectionTestUtils.setField(s, "id", 1L);
        ReflectionTestUtils.setField(s, "createdAt", java.time.LocalDateTime.of(2026, 6, 3, 13, 0));
        return s;
    }

    // ==========================================================================
    // list(userPublicId, statusFilter, page, size) — 정기 송금 내역 조회
    // ==========================================================================

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("list 정상: status=null이면 전체 페이지 조회, 응답 메타·아이템 매핑")
    void list_정상_전체() {
        ScheduledTransfer s1 = stampScheduled(101L, ScheduledTransferStatus.ACTIVE, "NGUYEN VAN A");
        ScheduledTransfer s2 = stampScheduled(102L, ScheduledTransferStatus.PAUSED, "Linh");

        org.springframework.data.domain.Page<ScheduledTransfer> pageResult =
                new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(s1, s2),
                        org.springframework.data.domain.PageRequest.of(0, 20,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")),
                        2L);
        given(scheduledTransferRepository.findByUserPublicId(
                org.mockito.ArgumentMatchers.eq(SENDER),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .willReturn(pageResult);

        var resp = service.list(SENDER, null, 0, 20);

        assertThat(resp.scheduledTransfers()).hasSize(2);
        assertThat(resp.scheduledTransfers().get(0).receiverName()).isEqualTo("NGUYEN VAN A");
        assertThat(resp.scheduledTransfers().get(0).status()).isEqualTo("ACTIVE");
        assertThat(resp.page()).isEqualTo(0);
        assertThat(resp.size()).isEqualTo(20);
        assertThat(resp.totalElements()).isEqualTo(2L);
        assertThat(resp.totalPages()).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("list status=ACTIVE 필터: findByUserPublicIdAndStatus 경로 호출")
    void list_status_ACTIVE_필터() {
        ScheduledTransfer s1 = stampScheduled(101L, ScheduledTransferStatus.ACTIVE, "NGUYEN VAN A");

        org.springframework.data.domain.Page<ScheduledTransfer> pageResult =
                new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(s1),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        1L);
        given(scheduledTransferRepository.findByUserPublicIdAndStatus(
                org.mockito.ArgumentMatchers.eq(SENDER),
                org.mockito.ArgumentMatchers.eq(ScheduledTransferStatus.ACTIVE),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .willReturn(pageResult);

        var resp = service.list(SENDER, "ACTIVE", 0, 20);

        assertThat(resp.scheduledTransfers()).hasSize(1);
        assertThat(resp.totalElements()).isEqualTo(1L);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("list: 잘못된 status 값 → COMMON4001")
    void list_잘못된_status_COMMON4001() {
        assertThatThrownBy(() -> service.list(SENDER, "INVALID_STATUS", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    // ==========================================================================
    // getHistory(userPublicId, transferPublicId, page, size) — 회차 실행 이력 조회
    // ==========================================================================

    private static final String ST_PUBLIC_ID = "st-pub-uuid-123";

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getHistory 정상: 본인 정기송금 + 회차 transactions 페이지 응답")
    void getHistory_정상() {
        ScheduledTransfer st = stampScheduled(200L, ScheduledTransferStatus.ACTIVE, "NGUYEN VAN A");
        ReflectionTestUtils.setField(st, "publicId", ST_PUBLIC_ID);
        given(scheduledTransferRepository.findByPublicId(ST_PUBLIC_ID)).willReturn(Optional.of(st));

        Transaction tx1 = stampTx(900L, "tx-pub-1", new java.math.BigDecimal("500000"));
        Transaction tx2 = stampTx(901L, "tx-pub-2", new java.math.BigDecimal("500000"));
        org.springframework.data.domain.Page<Transaction> txPage =
                new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(tx1, tx2),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        2L);
        given(transactionRepository.findByIdempotencyKeyStartingWith(
                org.mockito.ArgumentMatchers.eq("scheduled:" + ST_PUBLIC_ID + ":"),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .willReturn(txPage);

        var resp = service.getHistory(SENDER, ST_PUBLIC_ID, 0, 20);

        assertThat(resp.histories()).hasSize(2);
        assertThat(resp.histories().get(0).publicId()).isEqualTo("tx-pub-1");
        assertThat(resp.histories().get(0).status()).isEqualTo("COMPLETED");
        assertThat(resp.totalElements()).isEqualTo(2L);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getHistory: 정기송금 미존재 → TRANSFER4001 (정보 누설 방지)")
    void getHistory_정기송금_없음_TRANSFER4001() {
        given(scheduledTransferRepository.findByPublicId(ST_PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory(SENDER, ST_PUBLIC_ID, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        // 본인 검증 실패라 transactions 조회 미진입.
        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getHistory: 다른 사용자의 정기송금 → TRANSFER4001 (cross-user 차단)")
    void getHistory_본인아님_TRANSFER4001() {
        // 다른 사용자 소유 ScheduledTransfer
        ScheduledTransfer otherUserSt = ScheduledTransfer.builder()
                .publicId(ST_PUBLIC_ID)
                .userPublicId("other-user-uuid")  // SENDER 아님
                .transferType(TransactionType.REMITTANCE)
                .bankAccountId(BANK_ACC_ID)
                .amount(new java.math.BigDecimal("500000"))
                .currencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .receiveCurrencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .frequency(TransferFrequency.MONTHLY)
                .scheduleDay(25)
                .nextRunDate(FIXED_NEXT)
                .status(ScheduledTransferStatus.ACTIVE)
                .build();
        given(scheduledTransferRepository.findByPublicId(ST_PUBLIC_ID))
                .willReturn(Optional.of(otherUserSt));

        assertThatThrownBy(() -> service.getHistory(SENDER, ST_PUBLIC_ID, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(TransferErrorCode.TRANSFER_NOT_FOUND);

        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("getHistory 회차 0개: 빈 배열 + total_elements=0")
    void getHistory_회차_없음() {
        ScheduledTransfer st = stampScheduled(201L, ScheduledTransferStatus.ACTIVE, "NGUYEN VAN A");
        ReflectionTestUtils.setField(st, "publicId", ST_PUBLIC_ID);
        given(scheduledTransferRepository.findByPublicId(ST_PUBLIC_ID)).willReturn(Optional.of(st));

        org.springframework.data.domain.Page<Transaction> empty =
                new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        0L);
        given(transactionRepository.findByIdempotencyKeyStartingWith(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .willReturn(empty);

        var resp = service.getHistory(SENDER, ST_PUBLIC_ID, 0, 20);

        assertThat(resp.histories()).isEmpty();
        assertThat(resp.totalElements()).isZero();
        assertThat(resp.totalPages()).isZero();
    }

    /** getHistory 테스트용 Transaction 헬퍼. */
    private Transaction stampTx(long id, String publicId, java.math.BigDecimal amount) {
        Transaction tx = Transaction.builder()
                .publicId(publicId)
                .wallet(wallet(1L, SENDER))
                .type(TransactionType.REMITTANCE)
                .amount(amount)
                .currencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .fee(new java.math.BigDecimal("3000"))
                .status(com.gb.wallet.global.common.enums.TransactionStatus.COMPLETED)
                .idempotencyKey("scheduled:" + ST_PUBLIC_ID + ":2026-06-25")
                .bankAccountId(BANK_ACC_ID)
                .receiveAmount(amount)
                .receiveCurrencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .build();
        ReflectionTestUtils.setField(tx, "id", id);
        ReflectionTestUtils.setField(tx, "createdAt", java.time.LocalDateTime.of(2026, 6, 25, 12, 0));
        return tx;
    }

    /** list 테스트용 ScheduledTransfer 헬퍼. */
    private ScheduledTransfer stampScheduled(long id, ScheduledTransferStatus status, String receiverName) {
        ScheduledTransfer s = ScheduledTransfer.builder()
                .publicId("st-pub-" + id)
                .userPublicId(SENDER)
                .transferType(TransactionType.REMITTANCE)
                .bankAccountId(BANK_ACC_ID)
                .receiverName(receiverName)
                .amount(new java.math.BigDecimal("500000.0000"))
                .currencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .receiveCurrencyCode(com.gb.wallet.global.common.enums.CurrencyType.KRW)
                .frequency(TransferFrequency.MONTHLY)
                .scheduleDay(25)
                .nextRunDate(FIXED_NEXT)
                .status(status)
                .build();
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "createdAt", java.time.LocalDateTime.of(2026, 6, 3, 13, 0));
        return s;
    }
}
