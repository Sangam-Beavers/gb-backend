package com.gb.wallet.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redis 빈 등록.
 *
 * <p>Lettuce 기반 {@code RedisTemplate}/{@code StringRedisTemplate}은 spring-boot-starter-data-redis
 * autoconfig가 제공한다(여기서 따로 빈 등록 안 함). 이 클래스는 Redisson 클라이언트만 명시 등록한다 —
 * 분산 락(MultiLock) + RBucket 멱등 캐시 등 송금 실행 흐름에 필요한 고급 기능을 위해서.
 *
 * <p>호스트/포트/비밀번호/TLS는 환경별 yml({@code application-dev.yml}, {@code application-stage.yml} 등)의
 * {@code spring.data.redis.*}에서 주입한다. stage/prod의 ElastiCache는 전송 중 암호화(TLS)가 켜져 있어
 * {@code spring.data.redis.ssl.enabled=true}이며, 이 경우 Redisson 주소를 {@code rediss://}로 붙인다
 * (로컬/dev는 평문 {@code redis://}). 이 SSL 플래그를 Redisson이 직접 읽어야 하는 이유는 아래 빈 메서드 주석 참고.
 */
@Configuration
public class RedisConfig {

    /**
     * {@code @Lazy}: Redisson은 빈 생성 시점에 Redis로 즉시 연결을 시도한다. 테스트 환경(Redis 미가동)에서
     * 컨텍스트 로딩이 깨지는 걸 막기 위해 lazy로 둔다. 실제 호출(`getLock`/`getBucket`)이 일어날 때만
     * 인스턴스가 생성되며, 운영에선 첫 호출 시 한 번 연결되고 이후 재사용된다.
     * 테스트에서 실제 호출이 필요한 경우 {@code @MockitoBean RedissonClient}로 대체.
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean sslEnabled) {
        Config config = new Config();
        // ElastiCache의 전송 중 암호화(TLS) 여부에 따라 스킴 분기. Lettuce(RedisTemplate)는
        // spring.data.redis.ssl.enabled를 autoconfig가 읽지만, Redisson은 수동 설정이라
        // 같은 플래그를 여기서 직접 읽어 rediss://(TLS) / redis://(평문)로 갈라줘야 한다.
        // 안 그러면 TLS 전용 Redis에 평문으로 붙어 "Unable to connect to Redis server"로 깨진다.
        String scheme = sslEnabled ? "rediss://" : "redis://";
        SingleServerConfig single = config.useSingleServer()
                .setAddress(scheme + host + ":" + port)
                .setConnectTimeout(3000)
                .setTimeout(3000);
        if (!password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}

