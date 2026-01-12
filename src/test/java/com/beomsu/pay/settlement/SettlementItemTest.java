package com.beomsu.pay.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementItemTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 5);

    private static SettlementItem newItem() {
        return SettlementItem.of(1L, "order-1", 10_000, DATE);
    }

    @Test
    @DisplayName("팩토리는 PENDING_CONFIRMATION(구매확정 대기)으로 생성한다")
    void factoryStartsPendingConfirmation() {
        assertThat(newItem().getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("confirm: PENDING_CONFIRMATION → CONFIRMED")
    void confirmTransitions() {
        SettlementItem item = newItem();
        item.confirm();
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
    }

    @Test
    @DisplayName("confirm 멱등: CONFIRMED/SETTLED/CANCELED에서는 무시")
    void confirmIsGuarded() {
        SettlementItem settled = newItem();
        settled.confirm();
        settled.markSettled();
        settled.confirm();
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);

        SettlementItem canceled = newItem();
        canceled.cancel();
        canceled.confirm();
        assertThat(canceled.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
    }

    @Test
    @DisplayName("markSettled: CONFIRMED에서만 SETTLED로 전이")
    void markSettledOnlyFromConfirmed() {
        SettlementItem fromPending = newItem();
        fromPending.markSettled(); // PENDING_CONFIRMATION에서는 무시
        assertThat(fromPending.getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);

        SettlementItem confirmed = newItem();
        confirmed.confirm();
        confirmed.markSettled();
        assertThat(confirmed.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("cancel: PENDING_CONFIRMATION/CONFIRMED → CANCELED, SETTLED/CANCELED는 무시")
    void cancelGuards() {
        SettlementItem fromPending = newItem();
        fromPending.cancel();
        assertThat(fromPending.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);

        SettlementItem fromConfirmed = newItem();
        fromConfirmed.confirm();
        fromConfirmed.cancel();
        assertThat(fromConfirmed.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);

        SettlementItem settled = newItem();
        settled.confirm();
        settled.markSettled();
        settled.cancel(); // SETTLED는 취소로 되돌리지 않는다
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("reduce: 금액을 차감하되 0 미만으로 내려가지 않는다")
    void reduceFloorsAtZero() {
        SettlementItem item = newItem();
        item.reduce(3_000);
        assertThat(item.getAmount()).isEqualTo(7_000);

        item.reduce(999_999); // 잔액보다 큰 차감
        assertThat(item.getAmount()).isEqualTo(0);
    }
}
