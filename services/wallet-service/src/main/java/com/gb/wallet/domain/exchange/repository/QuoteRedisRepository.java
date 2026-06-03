package com.gb.wallet.domain.exchange.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.exchange.dto.QuoteData;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/**
 * 환전 견적(QuoteData)을 Redis에 TTL 5분으로 저장/조회한다.
 *
 * <p>견적은 "5분간 유효한 약속"이라 영구 저장(DB)이 아니라 Redis가 적합하다. TTL이 지나면 Redis가
 * 키를 자동 삭제하므로, 실행 시 조회해서 없으면 "견적 만료"(EXCHANGE4002)로 처리한다 —
 * 만료 시각을 별도로 계산·비교할 필요가 없다.
 *
 * <p>QuoteData(record)는 JSON 문자열로 직렬화해 RBucket에 담는다. 직렬화는 ObjectMapper로 처리하며,
 * 직렬화 실패는 견적을 진행할 수 없는 상황이라 {@code BusinessException}(COMMON5000)으로 던진다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class QuoteRedisRepository {

    private static final String KEY_PREFIX = "quote:";
    private static final long TTL_MINUTES = 5L;

    /** Redisson eager connect 회피 — IdempotencyCacheHelper와 동일 패턴(@Autowired @Lazy). */
    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper;

    /** 견적을 5분 TTL로 저장한다. */
    public void save(QuoteData quote) {
        try {
            String json = objectMapper.writeValueAsString(quote);
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + quote.quotePublicId());
            bucket.set(json, TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("견적 Redis 저장 실패. quotePublicId={}", quote.quotePublicId(), e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /** 견적 조회. 없거나(만료/미존재) 역직렬화 실패면 {@link Optional#empty()}. */
    public Optional<QuoteData> find(String quotePublicId) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + quotePublicId);
        String json = bucket.get();
        if (json == null) {
            return Optional.empty(); // 만료(TTL 경과) 또는 미존재
        }
        try {
            return Optional.of(objectMapper.readValue(json, QuoteData.class));
        } catch (Exception e) {
            log.warn("견적 Redis 역직렬화 실패. quotePublicId={}", quotePublicId, e);
            return Optional.empty();
        }
    }

    /**
     * 실행 완료된 견적을 삭제한다(재사용 방지). 없으면 no-op.
     *
     * <p>이미 환전이 완료된 뒤의 정리 작업이라 best-effort다 — Redis 일시 장애로 삭제가 실패해도 견적은
     * TTL(5분)로 자동 만료되므로, 예외를 전파해 완료된 거래 응답을 깨뜨리지 않고 경고만 남긴다(find의 비치명 패턴).
     */
    public void delete(String quotePublicId) {
        try {
            redissonClient.getBucket(KEY_PREFIX + quotePublicId).delete();
        } catch (Exception e) {
            log.warn("견적 Redis 삭제 실패(무시 — TTL로 자동 만료). quotePublicId={}", quotePublicId, e);
        }
    }
}
