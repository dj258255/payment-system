package com.beomsu.pay.payment.pg;

/**
 * PG 연동 추상화. 구현체를 갈아끼워 테스트(FakePgClient)·실PG(TossPgClient, Phase 후반)·
 * 멀티PG(Phase 8)를 지원한다. payment 모듈 내부 인터페이스다.
 */
public interface PgClient {

    PgApproveResult approve(PgApproveCommand command);

    PgCancelResult cancel(String paymentKey, long cancelAmount, String reason);
}
