package com.beomsu.pay.payment.pg;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
 *   <li><b>{@link PgUnreachableException}·서킷 오픈</b>(요청이 PG에 <b>닿지도 못함</b>) → 다음 PG로 failover.</li>
 *   <li><b>그 밖의 예외</b> → <b>failover 금지</b>, UNKNOWN으로 보존.</li>
 * </ul>
 * 모든 PG가 불가하면 UNKNOWN(TIMEOUT)으로 돌려 복구 배치에 맡긴다.
 *
 * <p><b>마지막 줄이 핵심이다.</b> 어댑터는 미확정도 예외로 표현한다 — 일시 오류 응답은 승인 여부를
 * 알 수 없고, 승인 금액 불일치는 PG가 이미 승인을 마친 뒤에 나온다. "예외면 요청이 안 닿은 것"으로
 * 읽으면 넘기지 않겠다고 선언한 상태가 그대로 넘어가 이중 결제가 된다. 그래서 넘겨도 되는 경우만
 * 전용 예외로 명시하고, 나머지는 전부 보존한다.
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
            } catch (PgUnreachableException | CallNotPermittedException e) {
                // 요청이 PG에 닿지도 못했다 → 그 PG에서는 아무 일도 일어나지 않았으므로 넘겨도 안전하다
                log.warn("PG [{}] 요청 미도달 — 다음 PG로 failover: {}", route.name(), e.getMessage());
            } catch (RuntimeException e) {
                // 요청은 나갔고 응답도 왔다(일시 오류 응답·금액 불일치 등) → 원 PG에서 승인이 났을 수
                // 있다. 예외라는 이유로 넘기면 이중 결제가 된다. 넘기지 않고 미확정으로 보존한다.
                log.warn("PG [{}] 응답이 불확실함 — failover 금지, 미확정으로 보존: {}",
                        route.name(), e.toString());
                return PgApproveResult.timeout("PG 응답 불확실 — " + e.getMessage());
            }
        }
        // 모든 PG 불가 → 미확정
        return PgApproveResult.timeout("모든 PG 사용 불가 — 승인 미확정");
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        // 조회/취소는 원 결제를 처리한 PG로 가야 하지만, 데모에서는 가용한 첫 PG로 시도한다.
        for (PgRoute route : routes) {
            if (route.circuitBreaker().getState() == CircuitBreaker.State.OPEN) continue;
            try {
                return route.circuitBreaker()
                        .executeSupplier(() -> route.adapter().cancel(command));
            } catch (PgUnreachableException | CallNotPermittedException e) {
                log.warn("PG [{}] 취소 요청 미도달 — 다음 PG: {}", route.name(), e.getMessage());
            }
            // 응답이 온 실패(취소 불가 확정·일시 오류)는 그대로 올린다. 다른 PG는 이 결제를 모르므로
            // 넘겨 봐야 "그런 거래 없음"만 돌아오고, 그 응답을 취소 실패로 읽으면 판단이 뒤집힌다.
        }
        throw new IllegalStateException("모든 PG에 취소 요청이 닿지 않음: " + command.paymentKey());
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        for (PgRoute route : routes) {
            if (route.circuitBreaker().getState() == CircuitBreaker.State.OPEN) continue;
            try {
                return route.circuitBreaker()
                        .executeSupplier(() -> route.adapter().query(paymentKey));
            } catch (PgUnreachableException | CallNotPermittedException e) {
                log.warn("PG [{}] 조회 요청 미도달 — 다음 PG: {}", route.name(), e.getMessage());
            }
        }
        // 아무 PG에도 못 물었다 → NOT_FOUND로 답하면 안 된다. 복구 배치가 그걸 "승인 안 됨"으로 읽고
        // 살아 있는 결제를 실패로 확정한다. 모르는 것은 확정하지 않고 다음 주기로 넘긴다.
        log.warn("모든 PG 조회 불가 — 확정하지 않고 다음 주기로 넘김 paymentKey={}", paymentKey);
        return new PgQueryResult(PgPaymentStatus.IN_PROGRESS, null);
    }

    /** 관측/테스트용: 각 경로의 현재 서킷 상태. */
    public List<PgRoute> routes() {
        return routes;
    }
}
