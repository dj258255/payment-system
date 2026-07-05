package com.beomsu.pay.payment.pg;

import com.beomsu.pay.payment.pg.TossPgClient.TossPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토스 응답 → 도메인 타입 매핑 검증 (HTTP 없이).
 */
class TossPgClientMappingTest {

    @Test
    @DisplayName("승인 응답 DONE → SUCCESS(method)")
    void confirmDone() {
        PgApproveResult r = TossPgClient.mapConfirm(new TossPayment("DONE", "카드"));
        assertThat(r.outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(r.method()).isEqualTo("카드");
    }

    @Test
    @DisplayName("승인 응답이 DONE이 아니면 FAILED")
    void confirmNotDone() {
        assertThat(TossPgClient.mapConfirm(new TossPayment("ABORTED", null)).outcome())
                .isEqualTo(PgOutcome.FAILED);
        assertThat(TossPgClient.mapConfirm(null).outcome()).isEqualTo(PgOutcome.FAILED);
    }

    @Test
    @DisplayName("조회 상태 매핑: DONE→APPROVED, CANCELED→CANCELED, 그 외→NOT_FOUND")
    void queryStatusMapping() {
        assertThat(TossPgClient.mapStatus("DONE")).isEqualTo(PgPaymentStatus.APPROVED);
        assertThat(TossPgClient.mapStatus("CANCELED")).isEqualTo(PgPaymentStatus.CANCELED);
        assertThat(TossPgClient.mapStatus("PARTIAL_CANCELED")).isEqualTo(PgPaymentStatus.CANCELED);
        assertThat(TossPgClient.mapStatus("IN_PROGRESS")).isEqualTo(PgPaymentStatus.NOT_FOUND);
        assertThat(TossPgClient.mapStatus(null)).isEqualTo(PgPaymentStatus.NOT_FOUND);
    }
}
