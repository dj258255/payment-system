package com.beomsu.pay.payment.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientPgClientTest {

    /** 장애를 주입할 수 있는 PG 더블. */
    static class FlakyPgClient implements PgClient {
        final AtomicInteger approveCalls = new AtomicInteger();
        final AtomicInteger queryCalls = new AtomicInteger();
        RuntimeException approveError;
        int queryFailuresRemaining;
        PgPaymentStatus queryStatus = PgPaymentStatus.APPROVED;

        @Override
        public PgApproveResult approve(PgApproveCommand c) {
            approveCalls.incrementAndGet();
            if (approveError != null) throw approveError;
            return PgApproveResult.success("CARD");
        }

        @Override
        public PgCancelResult cancel(String k, long a, String r) {
            return new PgCancelResult("tx");
        }

        @Override
        public PgQueryResult query(String k) {
            queryCalls.incrementAndGet();
            if (queryFailuresRemaining > 0) {
                queryFailuresRemaining--;
                throw new RuntimeException("일시적 조회 실패");
            }
            return new PgQueryResult(queryStatus, queryStatus == PgPaymentStatus.APPROVED ? "CARD" : null);
        }
    }

    @Test
    @DisplayName("PG 승인이 예외를 던져도 UNKNOWN(TIMEOUT)으로 돌린다 — 실패로 단정하지 않는다")
    void approveExceptionBecomesUnknown() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 연결 실패");
        ResilientPgClient client = new ResilientPgClient(flaky);

        PgApproveResult result = client.approve(new PgApproveCommand("pk", "order-1", 10_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("승인은 재시도하지 않는다 — 멱등키 없는 재시도는 이중결제 위험")
    void approveIsNotRetried() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 오류");
        ResilientPgClient client = new ResilientPgClient(flaky);

        client.approve(new PgApproveCommand("pk", "order-1", 10_000));

        assertThat(flaky.approveCalls.get()).isEqualTo(1); // 딱 한 번만 호출
    }

    @Test
    @DisplayName("PG 장애가 지속되면 서킷이 OPEN된다 — 이후엔 PG를 호출하지 않고 즉시 폴백")
    void circuitOpensAfterRepeatedFailures() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 다운");
        ResilientPgClient client = new ResilientPgClient(flaky);

        // 최소 호출 수(3) 이상 실패시켜 서킷을 연다
        for (int i = 0; i < 5; i++) {
            client.approve(new PgApproveCommand("pk" + i, "order", 10_000));
        }
        int callsBeforeOpen = flaky.approveCalls.get();

        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 서킷이 열린 뒤 추가 호출들은 PG에 닿지 않는다(폴백만)
        for (int i = 0; i < 3; i++) {
            PgApproveResult r = client.approve(new PgApproveCommand("pkX", "order", 10_000));
            assertThat(r.outcome()).isEqualTo(PgOutcome.TIMEOUT);
        }
        assertThat(flaky.approveCalls.get()).isEqualTo(callsBeforeOpen); // 델리게이트 호출 증가 없음
    }

    @Test
    @DisplayName("조회는 일시 실패 시 재시도로 흡수한다 (읽기라 안전)")
    void queryRetriesTransientFailure() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.queryFailuresRemaining = 2;          // 두 번 실패 후 성공
        ResilientPgClient client = new ResilientPgClient(flaky);

        PgQueryResult result = client.query("pk");

        assertThat(result.isApproved()).isTrue();
        assertThat(flaky.queryCalls.get()).isEqualTo(3); // 2회 실패 + 1회 성공
    }
}
