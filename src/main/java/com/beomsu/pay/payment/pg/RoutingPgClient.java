package com.beomsu.pay.payment.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * 멀티 PG 스마트 라우팅 + 자동 failover.
 *
 * <p>여러 PG 어댑터를 가중치 순으로 시도한다. 한 PG가 <b>장애</b>(예외/서킷 오픈)면 다음 PG로
 * 넘긴다(failover). 각 PG는 자체 서킷브레이커로 보호돼, 계속 실패하는 PG는 건너뛴다.
 *
 * <p>핵심 안전 규칙 — <b>아무 때나 failover하지 않는다</b>:
 * <ul>
 *   <li><b>SUCCESS</b> → 그대로 반환.</li>
 *   <li><b>FAILED</b>(카드 거절 등) → 그대로 반환. 다른 PG도 거절하므로 failover 무의미.</li>
 *   <li><b>TIMEOUT</b>(미확정) → 그대로 반환. <b>failover 금지</b> — 다른 PG로 재시도하면
 *       원 PG에서 이미 처리됐을 때 이중결제가 난다.</li>
 *   <li><b>예외/서킷 오픈</b>(PG가 요청을 받지 못함) → 다음 PG로 failover.</li>
 * </ul>
 * 모든 PG가 불가하면 UNKNOWN(TIMEOUT)으로 돌려 복구 배치에 맡긴다.
 */
public class RoutingPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingPgClient.class);

    /** 하나의 PG 경로: 이름 + 어댑터 + 가중치 + 전용 서킷브레이커. */
    public record PgRoute(String name, PgClient adapter, int weight, CircuitBreaker circuitBreaker) {
        public static PgRoute of(String name, PgClient adapter, int weight) {
            CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(5).minimumNumberOfCalls(3)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(5))
                    .build();
            return new PgRoute(name, adapter, weight, CircuitBreaker.of("pg-" + name, cfg));
        }
    }

    private final List<PgRoute> routes;

    public RoutingPgClient(List<PgRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("최소 1개의 PG 경로가 필요합니다.");
        }
        // 가중치 높은 PG부터 시도 (수수료·안정성 정책을 가중치로 표현)
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt(PgRoute::weight).reversed())
                .toList();
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        for (PgRoute route : routes) {
            if (route.circuitBreaker().getState() == CircuitBreaker.State.OPEN) {
                continue; // 이 PG는 장애 상태 — 건너뛴다
            }
            try {
                PgApproveResult result = route.circuitBreaker()
                        .executeSupplier(() -> route.adapter().approve(command));
                // SUCCESS/FAILED/TIMEOUT 모두 "PG가 응답했다" → failover하지 않는다.
                // (특히 TIMEOUT은 이중결제 위험 때문에 절대 다른 PG로 넘기지 않는다.)
                return result;
            } catch (RuntimeException e) {
                // PG가 요청을 받지 못함(연결 실패/서킷 오픈=CallNotPermittedException) → 다음 PG로 failover
                log.warn("PG [{}] 승인 불가 — 다음 PG로 failover: {}", route.name(), e.getMessage());
            }
        }
        // 모든 PG 불가 → 미확정
        return PgApproveResult.timeout("모든 PG 사용 불가 — 승인 미확정");
    }

    @Override
    public PgCancelResult cancel(String paymentKey, long cancelAmount, String reason) {
        // 조회/취소는 원 결제를 처리한 PG로 가야 하지만, 데모에서는 가용한 첫 PG로 시도한다.
        for (PgRoute route : routes) {
            if (route.circuitBreaker().getState() == CircuitBreaker.State.OPEN) continue;
            try {
                return route.circuitBreaker()
                        .executeSupplier(() -> route.adapter().cancel(paymentKey, cancelAmount, reason));
            } catch (RuntimeException e) {
                log.warn("PG [{}] 취소 불가 — 다음 PG: {}", route.name(), e.getMessage());
            }
        }
        throw new IllegalStateException("모든 PG 취소 불가: " + paymentKey);
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        for (PgRoute route : routes) {
            if (route.circuitBreaker().getState() == CircuitBreaker.State.OPEN) continue;
            try {
                return route.circuitBreaker()
                        .executeSupplier(() -> route.adapter().query(paymentKey));
            } catch (RuntimeException e) {
                log.warn("PG [{}] 조회 불가 — 다음 PG: {}", route.name(), e.getMessage());
            }
        }
        return new PgQueryResult(PgPaymentStatus.NOT_FOUND, null);
    }

    /** 관측/테스트용: 각 경로의 현재 서킷 상태. */
    public List<PgRoute> routes() {
        return routes;
    }
}
