package com.gb.wallet.global.redis;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Redisson MultiLock 기반 분산 락 헬퍼.
 *
 * <p>송금 실행처럼 두 wallet의 잔액을 한 트랜잭션에서 같이 다루는 경우, 두 행에 비관적 락을 거는
 * 순서가 노드별로 어긋나면 데드락이 난다. 이 헬퍼는 docs/remittance/api-spec.md §6-2 정책에 따라
 * {@code wallet_id 오름차순}으로 락을 잡는 Resource Ordering 패턴을 강제한다.
 *
 * <p>같은 wallet_id 두 번(자기 송금)은 호출 전에 Service에서 차단해야 한다(TRANSFER4004).
 * 여기서는 별도 검증하지 않는다 — 헬퍼는 정책 집행만, 도메인 검증은 Service 책임.
 */
@Slf4j
@Component
public class DistributedLockHelper {

    /**
     * {@code @Autowired @Lazy} 필드 주입: Redisson은 빈 생성 시 즉시 Redis로 연결을 시도하므로,
     * 테스트(Redis 미가동) 컨텍스트가 깨지지 않도록 lazy 프록시로 받는다. 실제 메서드 호출(`getLock`)
     * 시점에 진짜 빈이 만들어지고 연결된다.
     *
     * <p>{@code @RequiredArgsConstructor} + 생성자 주입은 Lombok이 필드의 {@code @Lazy}를 생성자
     * 파라미터로 복사하지 않아(프로젝트에 {@code lombok.config} 없음 — ChargeServiceImpl javadoc 참고)
     * lazy 효과가 사라진다. 자기참조 처리와 동일하게 필드 주입을 쓴다.
     */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    private static final long WAIT_TIME_SECONDS = 3L;
    private static final long LEASE_TIME_SECONDS = 5L;
    private static final String WALLET_LOCK_PREFIX = "lock:wallet:";

    /**
     * 두 wallet에 대한 MultiLock을 {@code wallet_id 오름차순}으로 획득한다.
     *
     * <p>호출자 책임: 반환된 {@link RLock}은 try-finally에서 {@code unlock()}해야 한다.
     * 획득 실패 시(타임아웃·인터럽트) {@code null} 반환 — 호출 측에서 적절한 비즈니스 에러로 매핑.
     *
     * <p>사용 예시:
     * <pre>{@code
     * RLock lock = helper.tryLockTwoWallets(senderWallet.getId(), receiverWallet.getId());
     * if (lock == null) { throw new BusinessException(...); }
     * try { /* 잔액 차감 + 증액 + 거래/감사 로그 INSERT *\/ } finally { lock.unlock(); }
     * }</pre>
     */
    public RLock tryLockTwoWallets(Long walletIdA, Long walletIdB) {
        long lowerId = Math.min(walletIdA, walletIdB);
        long higherId = Math.max(walletIdA, walletIdB);

        RLock lock1 = redissonClient.getLock(WALLET_LOCK_PREFIX + lowerId);
        RLock lock2 = redissonClient.getLock(WALLET_LOCK_PREFIX + higherId);
        RLock multiLock = redissonClient.getMultiLock(lock1, lock2);

        try {
            boolean acquired = multiLock.tryLock(
                    WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
            return acquired ? multiLock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            // Redis 장애(RedisException/RedissonShutdownException 등)도 "획득 실패"로 정규화한다(wallet-lock-1) —
            // 호출자(executeInternalTransferPath)는 null을 기대해 COMMON5031(503)로 매핑하므로, 여기서 새어 나가면
            // generic 500이 된다. 분산락 fail-closed(못 잡으면 거부) — tryLock과 동일 방어. 형제 메서드와 대칭.
            log.warn("두-wallet 분산 락 획득 실패 — null 반환(호출 측 503 매핑). walletIds={},{}", walletIdA, walletIdB, e);
            return null;
        }
    }

    /**
     * 단일 키에 대한 분산 락을 {@code wait 3s / lease 5s}로 획득한다(두-wallet MultiLock과 동일 타이밍 정책).
     *
     * <p><b>명시적 {@code leaseTime}(5s)은 의도적</b>이다 — Redisson watchdog 자동 갱신을 끈다. critical
     * section이 짧고(SELECT 몇 개 + INSERT 1), 노드가 try-finally의 {@code unlock} 없이 죽어도 5초 뒤 락이
     * 자동 해제돼 빠르게 복구된다(watchdog은 {@code lockWatchdogTimeout} 기본 30s까지 락을 붙들어 복구가
     * 느리다). 더 긴 critical section의 {@link #tryLockTwoWallets}(송금)도 같은 5s lease로 동작한다. 만에
     * 하나 critical section이 5s를 넘겨 락이 만료되는 좁은 경우의 잔여 위험(중복 등록)은 후속 DB UNIQUE
     * 제약이 최종 안전망으로 닫는다(현재 범위 밖 — 별도 마이그레이션 이슈).
     *
     * <p>계좌 등록을 user 단위로 직렬화하는 것처럼, 잠글 리소스가 하나뿐이라 Resource Ordering이 필요 없는
     * 경우에 쓴다. 네임스페이스는 호출자가 정한다(키 전체를 넘긴다) — MultiLock 메서드가 {@code lock:wallet:}을
     * 강제하는 것과 달리, 이 메서드는 도메인별 키({@code lock:account-register:{user}} 등)를 받는다.
     *
     * <p>호출자 책임: 반환된 {@link RLock}은 try-finally에서 {@code isHeldByCurrentThread()} 확인 후
     * {@code unlock()}한다. 획득 실패(타임아웃·인터럽트) 시 {@code null} 반환 — 호출 측에서 비즈니스 에러로 매핑.
     *
     * @param lockKey 전체 락 키(네임스페이스 포함, 예: {@code lock:account-register:{userPublicId}})
     */
    public RLock tryLock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
            return acquired ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            // Redis 장애(RedisException/RedissonShutdownException 등)도 "획득 실패"로 정규화한다 — 호출자는
            // null을 기대해 COMMON5031(503)로 매핑하므로, 여기서 새어 나가면 500이 된다. 분산락은 fail-closed
            // (못 잡으면 거부)라 통과시키지 않고 null로 반환한다(충전 캐시/rate-limit 헬퍼와 동일한 방어).
            log.warn("분산 락 획득 실패 — null 반환(호출 측 503 매핑). key={}", lockKey, e);
            return null;
        }
    }

    /**
     * <b>장기 실행(배치) 전용</b> 단일 키 분산 락 — {@code wait 3s} + <b>watchdog 자동 갱신</b>으로 획득한다(WSCH-03).
     *
     * <p>{@link #tryLock}과 달리 {@code leaseTime}을 주지 않는다 → Redisson watchdog이 보유 동안 lease를
     * 주기적으로 갱신해(lockWatchdogTimeout 기본 30s, 1/3 주기 갱신) <b>critical section이 5초를 넘겨도 락이
     * 만료되지 않는다.</b> 스케줄러 배치(정기 송금 일괄 실행)는 실행 시간이 5초를 쉽게 넘겨 고정 5s lease로는
     * 락이 도중에 풀려 2파드가 동시에 도는데(WSCH-03), watchdog으로 이를 막는다. 노드가 {@code unlock} 없이
     * 죽으면 watchdog 갱신이 멈춰 ~30s 뒤 자동 해제돼 다음 주기에 복구된다.
     *
     * <p>짧은 critical section(송금/계좌 등록)은 빠른 자동 해제가 중요해 여전히 고정 5s lease({@link #tryLock})를
     * 쓴다 — 이 메서드는 "끝까지 들고 있어야 하는 단일 인스턴스 배치"에만 쓴다.
     *
     * @param lockKey 전체 락 키(예: {@code scheduler:scheduled-transfer})
     */
    public RLock tryLockWithWatchdog(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // leaseTime 미지정 오버로드 → watchdog 활성(자동 갱신).
            boolean acquired = lock.tryLock(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
            return acquired ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.warn("분산 락(watchdog) 획득 실패 — null 반환. key={}", lockKey, e);
            return null;
        }
    }
}
