package com.beomsu.pay.payment.pg;

/**
 * PG 연동 추상화. 구현체를 갈아끼워 테스트(FakePgClient)·실PG(TossPgClient)·멀티PG(Phase 8)를 지원한다.
 */
public interface PgClient {

    PgApproveResult approve(PgApproveCommand command);

    PgCancelResult cancel(String paymentKey, long cancelAmount, String reason);

    /** 결제의 실제 상태를 PG에 조회한다. UNKNOWN 복구·대사의 핵심. */
    PgQueryResult query(String paymentKey);
}
