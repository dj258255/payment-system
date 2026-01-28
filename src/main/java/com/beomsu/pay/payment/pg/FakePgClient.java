package com.beomsu.pay.payment.pg;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발/테스트용 가짜 PG. 실제 PG 키·네트워크 없이 결제 플로우를 완주하고, Phase 2의 실패 시나리오를
 * 재현한다.
 *
 * <p>상태를 가진다: {@code approve}가 호출되면 PG 측 상태({@link #pgSideStatusOnApprove})를 기록해 두고,
 * 이후 {@link #query}가 그 값을 돌려준다. 이렇게 하면 <b>"우리는 타임아웃이었지만 PG에는 승인으로
 * 남아 있는"</b> 상황(복구 배치가 승인으로 확정)과 <b>"PG에 아예 없는"</b> 상황(망취소/실패)을 모두
 * 테스트할 수 있다.
 *
 * <p><b>pgDelegate 역할은 단일 PG 모드에서만</b>: 멀티 PG 라우팅({@code app.pg.routing.enabled=true})을
 * 켜면 {@link RoutingPgClient}가 {@code @Qualifier("pgDelegate")} 자리를 대신 차지한다. 같은 qualifier가
 * 둘이면 주입이 모호해지므로, 라우팅이 켜지면 이 빈은 등록되지 않는다(라우팅 config가 자체 FakePg
 * 인스턴스로 경로를 구성한다).
 */
@Component
@Qualifier("pgDelegate")
@Profile("!prod")
@ConditionalOnProperty(name = "app.pg.routing.enabled", havingValue = "false", matchIfMissing = true)
public class FakePgClient implements PgClient {

    /** 다음 approve 호출이 <b>우리에게</b> 돌려줄 결과 (SUCCESS/FAILED/TIMEOUT) */
    private final AtomicReference<PgApproveResult> nextApproveResult =
            new AtomicReference<>(PgApproveResult.success("CARD"));

    /** approve 시 <b>PG 측에</b> 남길 실제 상태 (query가 이 값을 돌려준다) */
    private final AtomicReference<PgPaymentStatus> pgSideStatusOnApprove =
            new AtomicReference<>(PgPaymentStatus.APPROVED);

    private final Map<String, PgPaymentStatus> pgSide = new ConcurrentHashMap<>();

    public void setNextResult(PgApproveResult result) {
        nextApproveResult.set(result);
    }

    /** approve 시 PG 측에 남길 실제 상태를 지정한다 (타임아웃인데 실제론 승인된 상황 재현용). */
    public void setPgSideStatusOnApprove(PgPaymentStatus status) {
        pgSideStatusOnApprove.set(status);
    }

    public void reset() {
        nextApproveResult.set(PgApproveResult.success("CARD"));
        pgSideStatusOnApprove.set(PgPaymentStatus.APPROVED);
        pgSide.clear();
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        // 우리에게 무엇을 돌려주든(성공/타임아웃), PG 측에는 지정된 상태를 남긴다.
        pgSide.put(command.paymentKey(), pgSideStatusOnApprove.get());
        return nextApproveResult.get();
    }

    @Override
    public PgCancelResult cancel(String paymentKey, long cancelAmount, String reason) {
        pgSide.put(paymentKey, PgPaymentStatus.CANCELED);
        return new PgCancelResult("fake-tx-" + paymentKey + "-" + System.nanoTime());
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        PgPaymentStatus status = pgSide.getOrDefault(paymentKey, PgPaymentStatus.NOT_FOUND);
        String method = status == PgPaymentStatus.APPROVED ? "CARD" : null;
        return new PgQueryResult(status, method);
    }
}
