package com.gb.wallet.global.redis;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 멱등성 응답 캐시 헬퍼 (Layer 1).
 *
 * <p>docs/remittance/api-spec.md §6-1의 3-layer 멱등성 중 첫 번째 — 가장 빠른 경로. 동일
 * idempotency_key 재요청 시 DB·외부 호출을 건너뛰고 캐시된 JSON 응답을 즉시 돌려준다.
 * DB UNIQUE(layer 2)와 audit_log 재구성(layer 3)이 fallback 역할을 한다.
 *
 * <p>JSON 직렬화 자체는 호출 측 책임이다(Service가 ObjectMapper 사용). 헬퍼는 String만 다룬다 —
 * 캐시 계층이 도메인 DTO 형태에 결합되지 않도록.
 */
@Component
public class IdempotencyCacheHelper {

    /**
     * {@code @Autowired @Lazy}: 이유는 {@link DistributedLockHelper#redissonClient} 주석 참고.
     * (Redisson eager connect 회피 + Lombok {@code @RequiredArgsConstructor}가 {@code @Lazy}를
     * 생성자 파라미터로 못 옮기는 한계 우회)
     */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    private static final String KEY_PREFIX = "idempotency:";
    private static final long TTL_HOURS = 24L;

    /** 멱등성 키로 캐시된 응답 조회. 없으면 {@link Optional#empty()}. */
    public Optional<String> get(String idempotencyKey) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + idempotencyKey);
        return Optional.ofNullable(bucket.get());
    }

    /** 응답 JSON 문자열을 24시간 TTL로 캐시. 같은 키 재요청 시 {@link #get}으로 회수된다. */
    public void set(String idempotencyKey, String jsonResponse) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + idempotencyKey);
        bucket.set(jsonResponse, TTL_HOURS, TimeUnit.HOURS);
    }
}
