package com.beomsu.pay.fraud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisVelocityCounter 단위 테스트 — StringRedisTemplate 목. 고정 윈도우 INCR/EXPIRE 규약과
 * Redis 장애 시 fail-open(이번 시도만 카운트)을 검증한다(RateLimiter와 같은 패턴).
 */
@ExtendWith(MockitoExtension.class)
class RedisVelocityCounterTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    private RedisVelocityCounter counter() {
        return new RedisVelocityCounter(redis);
    }

    @Test
    @DisplayName("첫 INCR(count=1)이면 60초 TTL(EXPIRE)을 걸고 1을 반환한다")
    void firstHitSetsExpireAndReturnsOne() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(startsWith("velocity:card:c1:"))).thenReturn(1L);

        int count = counter().recordAndCount("card:c1");

        assertThat(count).isEqualTo(1);
        verify(redis).expire(startsWith("velocity:card:c1:"),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("후속 INCR(count>1)은 그 값을 반환하고 EXPIRE는 다시 걸지 않는다")
    void subsequentHitReturnsCountNoExpire() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(4L);

        int count = counter().recordAndCount("card:c1");

        assertThat(count).isEqualTo(4);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 예외 시 fail-open — 1을 반환하고 EXPIRE를 걸지 않는다(velocity 룰 통과)")
    void redisFailureFailOpen() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        int count = counter().recordAndCount("card:c1");

        assertThat(count).isEqualTo(1);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
