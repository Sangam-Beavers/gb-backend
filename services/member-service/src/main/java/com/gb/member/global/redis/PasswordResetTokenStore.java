package com.gb.member.global.redis;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 비밀번호 재설정 토큰을 Redis에 TTL로 저장한다(토큰 → email 매핑).
 *
 * <p>재설정 토큰은 "메일 받은 사람이 일정 시간 안에만 쓰는" 일회성 데이터라, 영구 저장(DB)이 아니라
 * Redis가 적합하다. TTL이 지나면 Redis가 자동 삭제하므로, 검증 시 조회해서 없으면 "만료/무효"로 처리한다
 * (만료 시각을 별도 계산할 필요 없음 — 환전 견적과 동일 전략).
 *
 * <p>저장 값은 email(문자열)뿐이라 {@link StringRedisTemplate}로 충분하다(JSON 직렬화 불필요).
 * 실제 비밀번호는 저장하지 않는다 — 토큰으로 본인 확인만 하고, 비번 변경은 IdP가 한다.
 */
@Repository
@RequiredArgsConstructor
public class PasswordResetTokenStore {

    private static final String KEY_PREFIX = "pwreset:";
    /** 재설정 토큰 유효 시간(분). 메일 받고 비번 바꾸기에 충분하면서 너무 길지 않게 30분. */
    private static final long TTL_MINUTES = 30;
    /** TTL은 {@link #TTL_MINUTES} 단일 출처에서 파생한다 — 메일 본문 문구와 어긋나지 않게(MEM-08). */
    private static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);

    private final StringRedisTemplate redisTemplate;

    /**
     * 재설정 토큰 유효 시간(분). 메일 본문의 "N분 내 유효" 문구가 TTL과 항상 일치하도록, 리터럴 대신
     * 이 값을 단일 출처로 노출한다(MEM-08). TTL을 바꾸면 메일 문구도 자동으로 따라간다.
     */
    public long ttlMinutes() {
        return TTL_MINUTES;
    }

    /** 토큰→email을 TTL로 저장한다. */
    public void save(String token, String email) {
        redisTemplate.opsForValue().set(KEY_PREFIX + token, email, TTL);
    }

    /**
     * 토큰을 원자적으로 소비한다(Redis GETDEL) — 조회와 삭제를 한 연산으로 처리해 토큰이 단 한 번만 쓰이게 한다.
     * 동시 요청·재시도가 같은 토큰으로 들어와도 정확히 한 호출만 email을 받고 나머지는 {@link Optional#empty()}다.
     * 없거나(만료/미존재/이미 소비) 비어 있으면 empty.
     */
    public Optional<String> consume(String token) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token));
    }
}
