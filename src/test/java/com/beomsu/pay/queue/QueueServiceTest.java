package com.beomsu.pay.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueueService 단위 테스트 — StringRedisTemplate/ZSetOperations 목. Redis 실동작 없이
 * 키 규약(ZSET score=도착순번), rank 기반 입장 판정, 재진입 멱등, 이탈(ZREM)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final String EVENT = "drop-2026";
    private static final String QUEUE_KEY = "queue:drop-2026";
    private static final String SEQ_KEY = "queue:drop-2026:seq";
    private static final String USER = "1";
    private static final int ADMIT_LIMIT = 100;

    @Mock
    StringRedisTemplate redis;
    @Mock
    ZSetOperations<String, String> zset;
    @Mock
    ValueOperations<String, String> valueOps;

    private QueueService service() {
        return new QueueService(redis, ADMIT_LIMIT);
    }

    @Test
    @DisplayName("enter(신규): INCR로 순번 발급 → ZADD, rank 기반 위치 반환")
    void enterNew() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.score(QUEUE_KEY, USER)).thenReturn(null);        // 아직 큐에 없음
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(SEQ_KEY)).thenReturn(1L);         // 도착순번 1
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(0L);           // 맨 앞
        when(zset.zCard(QUEUE_KEY)).thenReturn(1L);

        QueuePosition pos = service().enter(EVENT, USER);

        verify(zset).add(QUEUE_KEY, USER, 1.0d);                   // score=도착순번으로 ZADD
        assertThat(pos.position()).isEqualTo(1);                   // 1-based
        assertThat(pos.waitingAhead()).isEqualTo(0);
        assertThat(pos.admitted()).isTrue();                       // rank(0) < limit(100)
        assertThat(pos.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("enter(재진입): 이미 큐에 있으면 순번 재발급/ZADD 없이 기존 rank 반환(멱등)")
    void enterReentryIdempotent() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.score(QUEUE_KEY, USER)).thenReturn(5.0d);        // 이미 도착순번 5로 서 있음
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(5L);
        when(zset.zCard(QUEUE_KEY)).thenReturn(10L);

        QueuePosition pos = service().enter(EVENT, USER);

        verify(redis, never()).opsForValue();                      // INCR 안 함
        verify(zset, never()).add(anyString(), anyString(), anyDouble());  // ZADD 안 함 → 순서 안 밀림
        assertThat(pos.position()).isEqualTo(6);                   // rank 5 → 6번째
        assertThat(pos.admitted()).isTrue();
        assertThat(pos.total()).isEqualTo(10);
    }

    @Test
    @DisplayName("status(대기중, 경계 limit-1): rank가 limit 미만이면 입장(admitted=true)")
    void statusAdmittedAtBoundary() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(99L);          // limit-1 = 99 (100번째)
        when(zset.zCard(QUEUE_KEY)).thenReturn(500L);

        QueuePosition pos = service().status(EVENT, USER);

        assertThat(pos.admitted()).isTrue();                       // 99 < 100
        assertThat(pos.position()).isEqualTo(100);
        assertThat(pos.total()).isEqualTo(500);
    }

    @Test
    @DisplayName("status(대기중, 경계 limit): rank가 limit이면 미입장(admitted=false)")
    void statusNotAdmittedAtBoundary() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(100L);         // limit = 100 (101번째)
        when(zset.zCard(QUEUE_KEY)).thenReturn(500L);

        QueuePosition pos = service().status(EVENT, USER);

        assertThat(pos.admitted()).isFalse();                      // 100 < 100 == false
        assertThat(pos.waitingAhead()).isEqualTo(100);
        assertThat(pos.position()).isEqualTo(101);
    }

    @Test
    @DisplayName("status(큐에 없음): rank가 null이면 position=-1, admitted=false")
    void statusNotInQueue() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(null);
        when(zset.zCard(QUEUE_KEY)).thenReturn(3L);

        QueuePosition pos = service().status(EVENT, USER);

        assertThat(pos.position()).isEqualTo(-1);
        assertThat(pos.waitingAhead()).isEqualTo(-1);
        assertThat(pos.admitted()).isFalse();
        assertThat(pos.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("status: Redis 예외 시 fail-soft — 대기열 없음으로 처리(500 방지)")
    void statusFailSoft() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        QueuePosition pos = service().status(EVENT, USER);

        assertThat(pos.position()).isEqualTo(-1);                  // 예외 전파 안 함
        assertThat(pos.admitted()).isFalse();
    }

    @Test
    @DisplayName("leave: ZREM으로 줄에서 제거 → 뒷사람 rank 당김")
    void leave() {
        when(redis.opsForZSet()).thenReturn(zset);

        service().leave(EVENT, USER);

        verify(zset).remove(QUEUE_KEY, (Object) USER);
    }
}
