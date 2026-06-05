package com.gb.member.global.redis;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 요청 rate-limiter — 이메일 단위 고정 윈도(MEM-04).
 *
 * <p>제한이 없으면 한 이메일로 재설정 메일을 무제한 발송시켜 <b>메일 폭탄</b>이 가능하다. 이메일 단위로
 * 윈도 내 발송 횟수를 제한해 폭탄을 막는다(초과 시 호출 측이 COMMON4291로 거부). 가입 여부와 무관하게
 * 발송 진입 *전*에 적용해, 미가입/가입 사이의 처리 시간 차이도 일부 줄인다.
 *
 * <p><b>원자성:</b> {@code INCR} 후 첫 카운트일 때만 {@code PEXPIRE}를 거는 두 연산을 Lua로 묶어 원자 실행한다
 * (wallet RateLimitHelper와 동일 패턴). 분리하면 INCR 직후 프로세스가 죽을 때 TTL이 안 걸려 키가 영구
 * 잔존(영구 차단)할 수 있다.
 *
 * <p>Redis 장애 시 fail-open(통과) — rate-limit은 보안 보조 장치이고, 장애로 정상 사용자의 재설정을 막는
 * 쪽이 더 큰 피해다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetRateLimiter {

    private static final String KEY_PREFIX = "ratelimit:pwreset:";
    /** 이메일당 윈도 내 허용 발송 횟수. 운영 정책 확정 전 보수적 기본값. */
    private static final int LIMIT = 5;
    /** 고정 윈도 길이. */
    private static final Duration WINDOW = Duration.ofHours(1);

    /** INCR 후 첫 카운트면 PEXPIRE를 걸고 현재 카운트를 반환한다(원자). */
    private static final RedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 이메일 단위로 1회 시도를 센다. 윈도 내 카운트가 한도 이하면 {@code true}(허용), 초과면 {@code false}.
     * Redis 장애 시에는 {@code true}(fail-open)로 통과시킨다.
     *
     * <p><b>키 정규화(MEM2 회귀):</b> Redis 키는 byte-exact라 {@code "A@x"}/{@code "a@x"}/{@code "a@x "}가
     * 서로 다른 버킷이 되어 한도를 우회(피해자 메일 폭탄)할 수 있다. 키 생성 시 {@code trim().toLowerCase}로
     * 정규화해 같은 이메일의 대소문자·공백 변형을 한 버킷으로 모은다. ({@code findByEmail}은 MySQL 기본
     * collation으로 이미 대소문자 무시라 여기서만 정규화하면 충분하다. 토큰 저장·{@code changePassword}로
     * 흐르는 이메일은 입력값이 아니라 <b>회원의 저장 이메일</b>(가입 표기 = IdP username과 byte-exact 일치)을
     * 사용한다 — 11D member-idp-3. 본 정규화는 rate-limit 키에 한정한다.)
     */
    public boolean tryAcquire(String email) {
        try {
            String normalizedKey = KEY_PREFIX + email.trim().toLowerCase(Locale.ROOT);
            Long count = redisTemplate.execute(
                    INCR_EXPIRE, List.of(normalizedKey), String.valueOf(WINDOW.toMillis()));
            return count == null || count <= LIMIT;
        } catch (RuntimeException e) {
            log.warn("비밀번호 재설정 rate-limit 조회 실패 — fail-open(통과). email-hash 키 prefix={}", KEY_PREFIX, e);
            return true;
        }
    }
}
