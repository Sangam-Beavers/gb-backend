package com.gb.wallet.global.redis;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 소액이체(1원) 계좌 인증 confirm 완료 후 account_token을 Redis에 단명 보관한다.
 *
 * <p><b>왜 필요한가:</b> 구 방식은 {@code bankClient.verify()}가 즉시 token을 반환해 서버가 재호출하면
 * 됐으나, 1원 인증 방식에서는 {@code confirm}이 단 1회 token을 발급한다. 재confirm이면 Mock 은행이
 * 1원을 한 번 더 입금하므로 UX·비용 문제가 있다. 따라서 confirm 성공 시 서버가 token을 여기에 저장하고,
 * 이후 {@code registerAccount}가 원자 소비(GETDEL)해 사용한다.
 *
 * <p><b>키 형식:</b> {@code verify-session:account:{userPublicId}:{bankCode}:{accountNumber}}
 * (database.md §7 Redis 키 등록).
 *
 * <p><b>단일사용(GETDEL):</b> {@link #consume}은 {@link RBucket#getAndDelete()}로 원자화한다 —
 * 1회 confirm이 정확히 1회 register를 인가한다({@link PinVerificationStore}의 double-spend 방지와 동일 사상).
 *
 * <p><b>fail-closed:</b> 저장/소비 실패는 모두 예외를 전파한다 — 토큰이 없는 상태에서 계좌 등록이
 * 진행되면 안 된다(금융 정합성).
 */
@Slf4j
@Component
public class VerifySessionStore {

    private static final String KEY_PREFIX = "verify-session:account:";
    /** 1원 인증 세션 유효 시간(초) = Mock 은행 pending_verifications 만료(600초)와 맞춘다. */
    private static final long TTL_SECONDS = 600L;

    @Autowired
    @Lazy
    private RedissonClient redissonClient;

    /**
     * confirm 성공 후 account_token을 {TTL_SECONDS}초 보관한다.
     *
     * <p>저장 실패 시 fail-closed로 예외 전파 — token 없이 register가 진행되는 것을 막는다.
     */
    public void save(String userPublicId, String bankCode, String accountNumber, String accountToken) {
        String key = buildKey(userPublicId, bankCode, accountNumber);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(accountToken, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("인증 세션 토큰 저장 실패 — fail-closed. key={}", key, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * account_token을 <b>원자적으로 소비</b>한다(GETDEL).
     * 세션이 없거나 만료됐으면 {@code ACCOUNT4009}(403 → BAD_REQUEST).
     *
     * <p>Redis 장애로 결과가 불확실하면 예외 전파(fail-closed) — COMMON5000.
     */
    public String consume(String userPublicId, String bankCode, String accountNumber) {
        String key = buildKey(userPublicId, bankCode, accountNumber);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String token = bucket.getAndDelete();
            if (token == null) {
                throw new BusinessException(AccountErrorCode.VERIFY_SESSION_NOT_FOUND);
            }
            return token;
        } catch (BusinessException be) {
            throw be; // AccountErrorCode 예외는 그대로 전파
        } catch (Exception e) {
            log.error("인증 세션 토큰 소비(getAndDelete) 실패 — fail-closed. key={}", key, e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }

    private String buildKey(String userPublicId, String bankCode, String accountNumber) {
        return KEY_PREFIX + userPublicId + ":" + bankCode + ":" + accountNumber;
    }
}
