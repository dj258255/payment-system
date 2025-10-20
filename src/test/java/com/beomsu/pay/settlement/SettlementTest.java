package com.beomsu.pay.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    @Test
    @DisplayName("netAmount = grossAmount - feeAmount 불변식")
    void netEqualsGrossMinusFee() {
        Settlement settlement = Settlement.of(LocalDate.of(2026, 7, 5), 30_000, 900, 2);

        assertThat(settlement.getNetAmount()).isEqualTo(29_100);
        assertThat(settlement.getNetAmount())
                .isEqualTo(settlement.getGrossAmount() - settlement.getFeeAmount());
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CREATED);
    }

    @Test
    @DisplayName("수수료가 총액보다 크면 정산을 만들 수 없다")
    void feeCannotExceedGross() {
        assertThatThrownBy(() -> Settlement.of(LocalDate.of(2026, 7, 5), 1_000, 2_000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 금액으로는 정산을 만들 수 없다")
    void negativeAmountRejected() {
        assertThatThrownBy(() -> Settlement.of(LocalDate.of(2026, 7, 5), -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
