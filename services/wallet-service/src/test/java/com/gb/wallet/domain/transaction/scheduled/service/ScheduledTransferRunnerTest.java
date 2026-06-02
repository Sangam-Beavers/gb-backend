package com.gb.wallet.domain.transaction.scheduled.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.domain.transaction.scheduled.repository.ScheduledTransferRepository;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.TransferFrequency;
import com.gb.wallet.global.redis.DistributedLockHelper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ScheduledTransferRunner} 단위 테스트. 분산 락 분기·도래 행 위임·실패 격리·변환 헬퍼 검증.
 *
 * <p>self-injection은 ReflectionTestUtils로 자기 자신을 주입해 {@code executeSingle} 진짜 메서드를 타게 한다
 * (TransferServiceImpl 테스트와 동일 패턴). 단위 테스트 범위라 트랜잭션 경계(@Transactional) AOP는 미적용.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTransferRunnerTest {

    @Mock private ScheduledTransferRepository scheduledTransferRepository;
    @Mock private TransferService transferService;
    @Mock private NextRunDateCalculator nextRunDateCalculator;
    @Mock private DistributedLockHelper distributedLockHelper;
    @Mock private BankAccountRepository bankAccountRepository;
    @InjectMocks private ScheduledTransferRunner runner;

    @BeforeEach
    void injectSelf() {
        ReflectionTestUtils.setField(runner, "self", runner);
    }

    @Test
    @DisplayName("락 획득 실패(다른 인스턴스 실행 중) → 도래 행 조회조차 안 함")
    void runDueTransfers_락실패시_조용히_종료() {
        given(distributedLockHelper.tryLock(anyString())).willReturn(null);

        runner.runDueTransfers();

        verifyNoInteractions(scheduledTransferRepository, transferService);
    }

    @Test
    @DisplayName("REMITTANCE 정상 실행: bankAccount.publicId 풀어 TransferExecuteRequest 변환 + markExecuted")
    void runDueTransfers_REMITTANCE_정상() {
        RLock lock = mockLock();
        BankAccount account = bankAccount(99L, "bank-pub-uuid", "020", "Quokka Bank");
        ScheduledTransfer st = scheduled(10L, TransactionType.REMITTANCE, null, 99L,
                LocalDate.of(2020, 1, 1));  // 과거 — double-check(nextRunDate.isAfter(today)) 통과

        given(distributedLockHelper.tryLock(anyString())).willReturn(lock);
        given(scheduledTransferRepository.findAllByStatusAndNextRunDateLessThanEqual(
                eq(ScheduledTransferStatus.ACTIVE), any(LocalDate.class)))
                .willReturn(List.of(st));
        given(scheduledTransferRepository.findById(10L)).willReturn(Optional.of(st));
        given(bankAccountRepository.findById(99L)).willReturn(Optional.of(account));
        given(transferService.execute(anyString(), anyString(), any(TransferExecuteRequest.class)))
                .willReturn(Mockito.mock(TransferExecuteResponse.class));
        given(nextRunDateCalculator.calculateFrom(any(), Mockito.anyInt(), any(LocalDate.class)))
                .willReturn(LocalDate.of(2026, 7, 25));

        runner.runDueTransfers();

        // TransferService.execute가 변환된 request로 호출됨 (REMITTANCE + bank_account_public_id 채움)
        ArgumentCaptor<TransferExecuteRequest> reqCaptor =
                ArgumentCaptor.forClass(TransferExecuteRequest.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(transferService).execute(eq("user-uuid"), keyCaptor.capture(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().transferType()).isEqualTo("REMITTANCE");
        assertThat(reqCaptor.getValue().bankAccountPublicId()).isEqualTo("bank-pub-uuid");
        assertThat(reqCaptor.getValue().receiverPublicId()).isNull();
        // idempotency_key는 scheduled:{public_id}:{nextRunDate} 형태 — 회차 고정값.
        // today가 아닌 nextRunDate를 쓰는 이유: markExecuted 실패 후 재시도 시에도 같은 키로 멱등 보존
        // (CodeRabbit 리뷰 반영). ScheduledTransfer.nextRunDate = 2020-01-01로 박았으니 키도 그대로.
        assertThat(keyCaptor.getValue()).isEqualTo("scheduled:" + st.getPublicId() + ":2020-01-01");

        // 락 해제
        verify(lock).unlock();
    }

    @Test
    @DisplayName("INTERNAL_TRANSFER 정상 실행: receiver_public_id 그대로, bank_account_public_id는 null")
    void runDueTransfers_INTERNAL_정상() {
        RLock lock = mockLock();
        ScheduledTransfer st = scheduled(11L, TransactionType.INTERNAL_TRANSFER, "receiver-uuid", null,
                LocalDate.of(2020, 1, 1));  // 과거 — double-check(nextRunDate.isAfter(today)) 통과

        given(distributedLockHelper.tryLock(anyString())).willReturn(lock);
        given(scheduledTransferRepository.findAllByStatusAndNextRunDateLessThanEqual(any(), any()))
                .willReturn(List.of(st));
        given(scheduledTransferRepository.findById(11L)).willReturn(Optional.of(st));
        given(transferService.execute(anyString(), anyString(), any(TransferExecuteRequest.class)))
                .willReturn(Mockito.mock(TransferExecuteResponse.class));
        given(nextRunDateCalculator.calculateFrom(any(), Mockito.anyInt(), any(LocalDate.class)))
                .willReturn(LocalDate.of(2026, 7, 25));

        runner.runDueTransfers();

        ArgumentCaptor<TransferExecuteRequest> reqCaptor =
                ArgumentCaptor.forClass(TransferExecuteRequest.class);
        verify(transferService).execute(anyString(), anyString(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().transferType()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(reqCaptor.getValue().receiverPublicId()).isEqualTo("receiver-uuid");
        assertThat(reqCaptor.getValue().bankAccountPublicId()).isNull();

        // INTERNAL은 bank_account 조회 안 함
        verifyNoInteractions(bankAccountRepository);
    }

    @Test
    @DisplayName("한 회차 실패해도 다른 회차는 계속 진행 (실패 격리)")
    void runDueTransfers_한건실패_다른건_계속() {
        RLock lock = mockLock();
        ScheduledTransfer st1 = scheduled(20L, TransactionType.INTERNAL_TRANSFER, "r1", null,
                LocalDate.of(2020, 1, 1));  // 과거 — double-check(nextRunDate.isAfter(today)) 통과
        ScheduledTransfer st2 = scheduled(21L, TransactionType.INTERNAL_TRANSFER, "r2", null,
                LocalDate.of(2020, 1, 1));  // 과거 — double-check(nextRunDate.isAfter(today)) 통과

        given(distributedLockHelper.tryLock(anyString())).willReturn(lock);
        given(scheduledTransferRepository.findAllByStatusAndNextRunDateLessThanEqual(any(), any()))
                .willReturn(List.of(st1, st2));
        given(scheduledTransferRepository.findById(20L)).willReturn(Optional.of(st1));
        given(scheduledTransferRepository.findById(21L)).willReturn(Optional.of(st2));
        // 첫 회차 실패(잔액 부족 등), 두 번째는 정상
        willThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .given(transferService).execute(anyString(), Mockito.contains(st1.getPublicId()),
                        any(TransferExecuteRequest.class));
        given(transferService.execute(anyString(), Mockito.contains(st2.getPublicId()),
                any(TransferExecuteRequest.class)))
                .willReturn(Mockito.mock(TransferExecuteResponse.class));
        given(nextRunDateCalculator.calculateFrom(any(), Mockito.anyInt(), any(LocalDate.class)))
                .willReturn(LocalDate.of(2026, 7, 25));

        runner.runDueTransfers();

        // 두 번 다 시도됨 (첫 실패가 두 번째 건너뛰지 않음)
        verify(transferService, times(2)).execute(anyString(), anyString(), any(TransferExecuteRequest.class));
        verify(lock).unlock();
    }

    @Test
    @DisplayName("double-check: 조회 후 status가 ACTIVE 아니면 송금 스킵")
    void executeSingle_status_변경_스킵() {
        ScheduledTransfer paused = scheduled(30L, TransactionType.INTERNAL_TRANSFER, "r1", null,
                LocalDate.of(2020, 1, 1));  // 과거 — double-check(nextRunDate.isAfter(today)) 통과
        paused.pause();  // ACTIVE → PAUSED
        given(scheduledTransferRepository.findById(30L)).willReturn(Optional.of(paused));

        runner.executeSingle(30L, LocalDate.of(2026, 6, 25));

        verify(transferService, never()).execute(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("double-check: next_run_date가 미래(today 이후)면 스킵 (이미 markExecuted된 상태)")
    void executeSingle_next_run_date_미도래_스킵() {
        ScheduledTransfer future = scheduled(31L, TransactionType.INTERNAL_TRANSFER, "r1", null,
                LocalDate.of(2026, 7, 25));  // 미래
        given(scheduledTransferRepository.findById(31L)).willReturn(Optional.of(future));

        runner.executeSingle(31L, LocalDate.of(2026, 6, 25));

        verify(transferService, never()).execute(anyString(), anyString(), any());
    }

    // ===== helpers =====

    private RLock mockLock() {
        RLock lock = Mockito.mock(RLock.class);
        given(lock.isHeldByCurrentThread()).willReturn(true);
        return lock;
    }

    private ScheduledTransfer scheduled(long id, TransactionType type, String receiverPublicId,
                                        Long bankAccountId, LocalDate nextRunDate) {
        ScheduledTransfer s = ScheduledTransfer.builder()
                .publicId("st-pub-" + id)
                .userPublicId("user-uuid")
                .transferType(type)
                .receiverPublicId(receiverPublicId)
                .bankAccountId(bankAccountId)
                .receiverName("NGUYEN VAN A")
                .amount(new BigDecimal("500000.0000"))
                .currencyCode(CurrencyType.KRW)
                .receiveCurrencyCode(CurrencyType.KRW)
                .frequency(TransferFrequency.MONTHLY)
                .scheduleDay(25)
                .nextRunDate(nextRunDate)
                .status(ScheduledTransferStatus.ACTIVE)
                .memo("매달 생활비")
                .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private BankAccount bankAccount(long id, String publicId, String bankCode, String bankName) {
        Bank bank = Bank.builder()
                .code(bankCode).name(bankName).country("VN").isDomestic(false).isActive(true).build();
        BankAccount a = BankAccount.builder()
                .publicId(publicId)
                .userPublicId("user-uuid")
                .bank(bank)
                .accountNumber("1002345678901")
                .holderName("NGUYEN VAN A")
                .isVirtual(false).isPrimary(false).isActive(true)
                .build();
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }
}
