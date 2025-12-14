package com.beomsu.pay.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationResultTest {

    @Test
    @DisplayName("resolveManually: PENDING 불일치를 MANUALLY_RESOLVED로 확정한다")
    void resolveManuallyFromPending() {
        ReconciliationResult r = ReconciliationResult.internalOnly("ord-1", 10_000);
        assertThat(r.getStatus()).isEqualTo(ReconStatus.PENDING);

        r.resolveManually();

        assertThat(r.getStatus()).isEqualTo(ReconStatus.MANUALLY_RESOLVED);
    }

    @Test
    @DisplayName("resolveManually: 자동 종결(AUTO_RESOLVED) 건은 수기 확정할 수 없다")
    void resolveManuallyRejectsAutoResolved() {
        ReconciliationResult matched = ReconciliationResult.matched("ord-1", 10_000);

        assertThatThrownBy(matched::resolveManually)
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("resolveManually: 이미 수기 확정된 건을 다시 확정하면 예외(멱등 아님 — 상태 가드)")
    void resolveManuallyRejectsAlreadyResolved() {
        ReconciliationResult r = ReconciliationResult.amountMismatch("ord-1", 10_000, 9_000);
        r.resolveManually();

        assertThatThrownBy(r::resolveManually)
                .isInstanceOf(ReconciliationException.class);
    }
}
