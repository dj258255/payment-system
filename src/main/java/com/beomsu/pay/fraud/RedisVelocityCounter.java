package com.beomsu.pay.fraud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis <b>고정 윈도우(fixed window)</b> velocity 카운터 — 다중 인스턴스에서 정확하다.
 *
 * <p>{@link InMemoryVelocityCounter}는 프로세스 로컬이라 인스턴스가 여러 대면 카운트가 갈라져
 * velocity 룰이 무력해진다. 여기서는 공유 Redis에 키 {@code velocity:{key}:{epochMinute}}를 INCR하고,
 * 첫 INCR(=1)일 때만 60초 TTL을 걸어 지난 윈도우 키가 자동 소멸하게 한다. INCR가 원자적이라
 * 다중 인스턴스에서도 카운트가 정확하다({@link com.beomsu.pay.RateLimiter}와 같은 패턴).
 *
 * <p><b>고정 윈도우의 한계</b>: epochMinute 경계 직전·직후에 몰아 치면 순간적으로 최대 2×윈도우까지
 * 통과할 수 있다. velocity는 "이상 급증 신호"용이라 이 정도 오차는 수용한다.
 *
 * <p><b>Redis 예외 시 fail-open</b> — 이번 시도만 센 것으로 보고 {@code 1}을 반환한다(velocity 룰
 * 통과) + warn. velocity 카운터가 죽었다고 결제를 막으면 가용성이 무너지므로 가용성을 우선한다
 * ({@link com.beomsu.pay.TokenStore#isRevoked}와 같은 판단).
 *
 * <p>{@code app.fds.velocity.redis=false} 면 이 빈이 비활성화되어 {@link InMemoryVelocityCounter}로
 * 폴백한다(기본 활성). @Primary 라 둘 다 있을 때 이 빈이 주입된다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.fds.velocity.redis", havingValue = "true", matchIfMissing = true)
public class RedisVelocityCounter implements VelocityCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisVelocityCounter.class);

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public RedisVelocityCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int recordAndCount(String key) {
        try {
            long epochMinute = Instant.now().getEpochSecond() / WINDOW.getSeconds();
            String redisKey = "velocity:" + key + ":" + epochMinute;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                // 첫 요청이 윈도우 키를 만들었다 → 윈도우 길이만큼 TTL을 걸어 자동 정리.
                redis.expire(redisKey, WINDOW);
            }
            return count == null ? 1 : count.intValue();
        } catch (RuntimeException e) {
            log.warn("velocity Redis 실패 — fail-open(이번 시도만 카운트)으로 처리합니다. key={}, err={}",
                    key, e.toString());
            return 1;
        }
    }
}
