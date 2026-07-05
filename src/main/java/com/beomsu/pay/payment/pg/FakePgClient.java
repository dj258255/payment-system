package com.beomsu.pay.payment.pg;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발/테스트용 가짜 PG. 실제 PG 키·네트워크 없이 결제 플로우를 완주할 수 있게 한다.
 * 다음 결과를 주입해 Phase 2의 실패 시나리오(타임아웃/거절)를 재현한다.
 */
@Component
@Profile("!prod")
public class FakePgClient implements PgClient {

    private final AtomicReference<PgApproveResult> nextResult =
            new AtomicReference<>(PgApproveResult.success("CARD"));

    /** 다음 approve 호출의 결과를 지정한다 (테스트용). */
    public void setNextResult(PgApproveResult result) {
        nextResult.set(result);
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        return nextResult.get();
    }

    @Override
    public PgCancelResult cancel(String paymentKey, long cancelAmount, String reason) {
        return new PgCancelResult("fake-tx-" + paymentKey + "-" + System.nanoTime());
    }
}
