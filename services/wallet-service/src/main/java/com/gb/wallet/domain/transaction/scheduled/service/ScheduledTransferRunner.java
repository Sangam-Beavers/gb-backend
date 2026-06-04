package com.gb.wallet.domain.transaction.scheduled.service;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.domain.transaction.scheduled.repository.ScheduledTransferRepository;
import com.gb.wallet.domain.transaction.service.TransferService;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.redis.DistributedLockHelper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기 송금 자동 실행 스케줄러.
 *
 * <p><b>동작:</b> 매일 KST 새벽 1시(운영 기본)에 트리거되어 {@code status=ACTIVE}이고
 * {@code next_run_date <= today}인 정기 송금을 일괄 실행한다. 각 회차는 {@link TransferService#execute}를
 * 그대로 호출 — 멱등성 3-layer·락 재시도·rate-limit·remittance_attempts 모두 송금 본업 로직이 처리한다.
 *
 * <p><b>분산 락(Redisson):</b> K8s multi-replica 환경에서 모든 파드가 동시 트리거되더라도
 * {@code scheduler:scheduled-transfer} 락을 획득한 단 1개 파드만 실제 실행한다(이중 송금 방지).
 * 락 못 잡은 파드는 조용히 종료 — 다음 트리거에서 다시 경쟁.
 *
 * <p><b>트랜잭션 경계:</b> 각 정기 송금 회차는 {@link #executeSingle}로 분리 — 별도 트랜잭션
 * ({@code REQUIRES_NEW}). 한 회차가 실패해도 다른 회차는 계속 진행한다.
 *
 * <p><b>멱등성:</b> {@code idempotency_key = "scheduled:{public_id}:{nextRunDate}"} — 회차의 예약일을
 * 키에 포함해 회차마다 유니크. {@code today} 대신 {@code nextRunDate}를 쓰는 이유: 자금 이동은 별도
 * 트랜잭션(transferService.execute는 {@code Propagation.NOT_SUPPORTED})에서 커밋되고, 후속의
 * {@code markExecuted}가 어떤 이유로 실패/롤백되면 {@code nextRunDate}는 그대로 남는다. 다음 트리거에서
 * 같은 회차가 다시 잡힐 때 {@code today}로 키를 만들면 날짜가 달라 멱등이 깨져 이중 송금이 날 수 있는 반면,
 * {@code nextRunDate} 키는 회차 고정값이라 layer 1/2/3 멱등으로 1차 호출 결과를 재구성한다(자금 추가 차감 없음).
 *
 * <p><b>실패 정책:</b> 잔액 부족·외부 은행 장애·기타 예외는 로그만 남기고 status는 유지(ACTIVE).
 * 다음 트리거에서 자동 재시도(PAUSED 자동 전환은 resume API가 없는 현재 단계에서 데드락 위험이라 미적용).
 *
 * <p><b>cron:</b> {@code wallet.scheduled-transfer.cron} 프로퍼티로 외부 설정. 미지정 시 기본값
 * {@code 0 0 1 * * *} (KST 매일 새벽 1시). 데모/테스트 환경에서만 yml로 빠른 주기 (예: {@code 0 * * * * *})
 * 로 덮어쓴다.
 *
 * <p><b>실행되지 않는 케이스:</b> 누락 회차(여러 날 서버 다운 후 복구)는 가장 최근 1회만 실행한다 —
 * {@code next_run_date <= today} 조건이 한 번만 만족, markExecuted 후엔 다음 주기로 점프하므로 자연스럽게
 * 이중 실행 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTransferRunner {

    /** 분산 락 키 — 모든 인스턴스가 같은 키로 경쟁해 단 1개만 획득. */
    private static final String LOCK_KEY = "scheduler:scheduled-transfer";

    private final ScheduledTransferRepository scheduledTransferRepository;
    private final TransferService transferService;
    private final NextRunDateCalculator nextRunDateCalculator;
    private final DistributedLockHelper distributedLockHelper;
    private final BankAccountRepository bankAccountRepository;

    /**
     * self-injection: {@link #executeSingle}의 {@code @Transactional(REQUIRES_NEW)}이 AOP 프록시를
     * 거쳐 적용되도록 자기참조 빈을 받는다. 직접 호출({@code this.executeSingle()})은 프록시 우회라
     * 트랜잭션 미적용. {@code @Lazy}로 빈 생성 시점의 순환 참조 회피 (DistributedLockHelper 패턴 동일).
     */
    @Autowired
    @Lazy
    private ScheduledTransferRunner self;

    /**
     * 매일 KST 새벽 1시 트리거(운영 기본). 데모/테스트는 yml로 cron 덮어쓴다.
     * 시간대 명시 — {@code Asia/Seoul}. 미명시 시 서버 OS 시간대 따라 KST와 어긋날 수 있음.
     */
    @Scheduled(cron = "${wallet.scheduled-transfer.cron:0 0 1 * * *}", zone = "Asia/Seoul")
    public void runDueTransfers() {
        // (1) 분산 락 — multi-replica 환경에서 단 1개만 실행. 못 잡으면 그냥 종료(다음 트리거에서 재경쟁).
        RLock lock = distributedLockHelper.tryLock(LOCK_KEY);
        if (lock == null) {
            log.debug("정기송금 스케줄러 — 다른 인스턴스가 락 보유 중 (정상, 정상 분산 환경)");
            return;
        }
        try {
            // (2) 도래 행 조회 — ACTIVE & next_run_date <= today(KST).
            LocalDate today = LocalDate.now(NextRunDateCalculator.ZONE_KST);
            List<ScheduledTransfer> dues = scheduledTransferRepository
                    .findAllByStatusAndNextRunDateLessThanEqual(ScheduledTransferStatus.ACTIVE, today);

            log.info("정기송금 스케줄러 시작 — {}건 대상 (today_kst={})", dues.size(), today);

            int succeeded = 0;
            int failed = 0;
            for (ScheduledTransfer s : dues) {
                try {
                    // (3) 각 회차는 별도 트랜잭션 — self-proxy로 REQUIRES_NEW 적용.
                    //     한 건 실패해도 다른 건 계속 진행한다.
                    self.executeSingle(s.getId(), today);
                    succeeded++;
                } catch (Exception e) {
                    // 잔액 부족(WALLET4002), 외부 장애(COMMON5031), 계좌 인증 실패 등 도메인 에러 + 시스템 에러 모두.
                    // status는 유지(ACTIVE) — 다음 트리거에서 자동 재시도.
                    failed++;
                    log.warn("정기송금 회차 실패 — id={}, publicId={}, msg={}",
                            s.getId(), s.getPublicId(), e.getMessage());
                }
            }

            log.info("정기송금 스케줄러 완료 — 성공 {}건 / 실패 {}건", succeeded, failed);

        } finally {
            // 락 보유자 본인만 해제 (lease 만료로 다른 스레드가 가진 경우 안전).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 정기 송금 1회차 실행 — 별도 트랜잭션. self-proxy 전용(외부 호출 금지).
     *
     * <p>(a) ScheduledTransfer 재조회 → (b) double-check (status/next_run_date) → (c)
     * TransferExecuteRequest 변환 → (d) TransferService.execute 호출 → (e) markExecuted +
     * next_run_date 갱신.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeSingle(Long scheduledTransferId, LocalDate today) {
        ScheduledTransfer s = scheduledTransferRepository.findById(scheduledTransferId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

        // double-check — 조회 시점부터 본 트랜잭션 진입까지 race로 status/next_run_date가 바뀌었을 수 있음.
        if (s.getStatus() != ScheduledTransferStatus.ACTIVE) {
            log.debug("정기송금 회차 스킵 — status가 더 이상 ACTIVE 아님. id={}, status={}",
                    s.getId(), s.getStatus());
            return;
        }
        if (s.getNextRunDate().isAfter(today)) {
            log.debug("정기송금 회차 스킵 — next_run_date 미도래. id={}, next_run_date={}, today={}",
                    s.getId(), s.getNextRunDate(), today);
            return;
        }

        // 송금 실행 — TransferService.execute가 멱등성·재시도·잔액·외부 호출 다 처리.
        // idempotency_key = "scheduled:{public_id}:{nextRunDate}" — 회차 고정값.
        //   today 대신 nextRunDate를 쓰는 이유(클래스 javadoc 멱등성 단락 참고):
        //   ① execute()는 NOT_SUPPORTED라 별도 트랜잭션에서 자금이 커밋되고
        //   ② 후속 markExecuted가 실패/롤백되면 nextRunDate는 그대로 남아
        //   ③ 다음 트리거에서 today 키였다면 날짜가 달라 멱등 우회 → 이중 송금 위험.
        //   nextRunDate 키는 회차 고정값이라 layer 1/2/3 멱등으로 1차 결과만 재구성된다.
        String idempotencyKey = "scheduled:" + s.getPublicId() + ":" + s.getNextRunDate();
        TransferExecuteRequest request = toExecuteRequest(s);
        transferService.execute(s.getUserPublicId(), idempotencyKey, request);

        // 다음 회차 계산 — today 기준 다음 주기. "오늘 이미 지났음" 정책으로 자연스럽게 다음 주/달로.
        LocalDate next = nextRunDateCalculator.calculateFrom(
                s.getFrequency(), s.getScheduleDay(), today);
        s.markExecuted(LocalDateTime.now(), next);
        // dirty checking으로 UPDATE — 본 메서드 종료 시 commit.
    }

    /**
     * {@link ScheduledTransfer} → {@link TransferExecuteRequest} 변환. REMITTANCE의 경우 저장된
     * {@code bankAccountId}(internal id)를 {@code public_id}로 풀어 박는다(execute API 시그니처).
     */
    private TransferExecuteRequest toExecuteRequest(ScheduledTransfer s) {
        String bankAccountPublicId = null;
        if (s.getTransferType() == TransactionType.REMITTANCE) {
            BankAccount account = bankAccountRepository.findById(s.getBankAccountId())
                    // 계좌가 사라진 경우(soft-delete 또는 실데이터 손상) — 정합성 비정상.
                    // 본 회차는 catch에서 잡혀 다음 트리거에 재시도된다(계좌 복원되면 정상화).
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
            bankAccountPublicId = account.getPublicId();
        }
        return new TransferExecuteRequest(
                s.getTransferType().name(),
                s.getAmount().toPlainString(),
                s.getCurrencyCode().name(),
                s.getReceiveCurrencyCode().name(),
                s.getMemo(),
                s.getReceiverPublicId(),    // INTERNAL만 사용, REMITTANCE면 null
                bankAccountPublicId          // REMITTANCE만 사용
        );
    }
}
