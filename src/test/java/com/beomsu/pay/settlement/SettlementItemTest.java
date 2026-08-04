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
    @DisplayName("confirm: PENDING_CONFIRMATION → CONFIRMED, confirmedDate를 릴리스일로 재스탬프")
    void confirmTransitions() {
        SettlementItem item = newItem(); // 적재 시 confirmedDate = DATE(승인일 placeholder)
        LocalDate releaseDate = DATE.plusDays(7);
        item.confirm(releaseDate);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        // 집계 기준일이 승인일이 아니라 릴리스일로 재스탬프돼야 한다(다일 에스크로 홀드 대응).
        assertThat(item.getConfirmedDate()).isEqualTo(releaseDate);
    }

    @Test
    @DisplayName("confirm 멱등: CONFIRMED/SETTLED/CANCELED에서는 무시")
    void confirmIsGuarded() {
        SettlementItem settled = newItem();
        settled.confirm(DATE);
        settled.markSettled();
        settled.confirm(DATE);
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);

        SettlementItem canceled = newItem();
        canceled.cancel();
        canceled.confirm(DATE);
        assertThat(canceled.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
    }

    @Test
    @DisplayName("markSettled: CONFIRMED에서만 SETTLED로 전이")
    void markSettledOnlyFromConfirmed() {
        SettlementItem fromPending = newItem();
        fromPending.markSettled(); // PENDING_CONFIRMATION에서는 무시
        assertThat(fromPending.getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);

        SettlementItem confirmed = newItem();
        confirmed.confirm(DATE);
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
        fromConfirmed.confirm(DATE);
        fromConfirmed.cancel();
        assertThat(fromConfirmed.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);

        SettlementItem settled = newItem();
        settled.confirm(DATE);
        settled.markSettled();
        settled.cancel(); // SETTLED는 취소로 되돌리지 않는다
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("applySettleableBalance: 금액을 잔액(절대값)으로 세팅 — 같은 값 재적용은 멱등, 음수는 0으로")
    void applySettleableBalanceSetsAbsolute() {
        SettlementItem item = newItem(); // amount 10,000
        item.applySettleableBalance(7_000);
        assertThat(item.getAmount()).isEqualTo(7_000);

        // 멱등: 같은 잔액을 다시 적용해도 그대로(델타 차감이 아님)
        item.applySettleableBalance(7_000);
        assertThat(item.getAmount()).isEqualTo(7_000);

        // 방어: 음수 잔액은 0으로 바닥 처리
        item.applySettleableBalance(-1);
        assertThat(item.getAmount()).isEqualTo(0);
    }
}
