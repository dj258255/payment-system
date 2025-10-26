package com.beomsu.pay.payment.pg;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * PG 호출에 서킷브레이커·재시도를 입히는 데코레이터.
 *
 * <p>PG 장애가 우리 전체로 번지지 않게 한다(스레드 고갈 방지). 설계 원칙:
 * <ul>
 *   <li><b>승인(approve)은 재시도하지 않는다.</b> 멱등키 없이 승인을 재시도하면 이중결제가 난다.
 *       대신 실패/서킷 오픈 시 {@link PgApproveResult#timeout}(=UNKNOWN)으로 돌려, 복구 배치가
 *       나중에 조회로 확정하게 한다.</li>
 *   <li><b>조회(query)는 읽기라 재시도가 안전하다.</b> 지수 백오프 + 지터로 일시 장애를 흡수한다.</li>
 * </ul>
 * {@code @Primary}라 {@code PaymentService}는 이 구현을 주입받는다. 실제 PG 어댑터가 생기기 전까지
 * {@link FakePgClient}를 위임 대상으로 감싼다.
 */
@Component
@Primary
public class ResilientPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientPgClient.class);

    private final PgClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry queryRetry;

    /**
     * 활성 PG 어댑터를 위임 대상으로 감싼다. 개발/테스트는 {@code FakePgClient}, 운영은
     * {@code TossPgClient}가 {@code @Qualifier("pgDelegate")}로 주입된다(프로파일로 택일).
     * 테스트에서는 이 생성자를 직접 호출해 장애 주입 더블을 감쌀 수 있다.
     */
    @Autowired
    public ResilientPgClient(@Qualifier("pgDelegate") PgClient delegate) {
        this.delegate = delegate;

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)                       // 절반 이상 실패하면 OPEN
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        this.circuitBreaker = CircuitBreaker.of("pg", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                // 지수 백오프 + 지터 — 재시도 폭풍(thundering herd) 방지
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(20), 2.0, 0.5))
                .ignoreExceptions(CallNotPermittedException.class)  // 서킷 오픈이면 재시도 무의미
                .build();
        this.queryRetry = Retry.of("pg-query", retryConfig);
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.approve(command));
        } catch (CallNotPermittedException open) {
            log.warn("PG 서킷 오픈 — 승인 미확정 처리: {}", command.orderNo());
            return PgApproveResult.timeout("서킷 오픈: PG 장애로 승인 미확정");
        } catch (RuntimeException ex) {
            // 예외를 실패로 단정하지 않는다 — PG에서 처리됐을 수도 있다 → UNKNOWN
            log.warn("PG 승인 호출 예외 — 미확정 처리: {}", ex.getMessage());
            return PgApproveResult.timeout("PG 오류로 승인 미확정: " + ex.getMessage());
        }
    }

    @Override
    public PgCancelResult cancel(String paymentKey, long cancelAmount, String reason) {
        // 취소도 서킷으로 보호하되 재시도는 하지 않는다(호출부가 실패를 처리).
        return circuitBreaker.executeSupplier(() -> delegate.cancel(paymentKey, cancelAmount, reason));
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        Supplier<PgQueryResult> guarded = Retry.decorateSupplier(queryRetry,
                () -> circuitBreaker.executeSupplier(() -> delegate.query(paymentKey)));
        // 조회 실패는 예외로 전파 — 복구 배치가 건별로 잡아 다음 주기에 다시 시도한다.
        return guarded.get();
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
