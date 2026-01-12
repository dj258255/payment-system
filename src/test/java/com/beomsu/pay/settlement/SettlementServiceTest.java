package com.beomsu.pay.settlement;

import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementServiceTest {

    private SettlementItemRepository itemRepository;
    private SettlementRepository settlementRepository;
    private MeterRegistry meterRegistry;
    private SettlementService service;

    private static final Instant APPROVED_AT = Instant.parse("2026-07-05T09:00:00Z");
    private static final LocalDate DATE = LocalDate.ofInstant(APPROVED_AT, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        itemRepository = mock(SettlementItemRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new SettlementService(itemRepository, settlementRepository, meterRegistry);
    }

    /** CONFIRMED 상태의 항목을 만든다(적재 후 에스크로 릴리스 반영된 상태). */
    private static SettlementItem confirmedItem(long paymentId, String orderNo, long amount) {
        SettlementItem item = SettlementItem.of(paymentId, orderNo, amount, DATE);
        item.confirm();
        return item;
    }

    @Test
    @DisplayName("결제 승인: 정산 항목을 PENDING_CONFIRMATION(구매확정 대기)으로 적재한다")
    void registersConfirmedPaymentAsItem() {
        when(itemRepository.existsByPaymentId(100L)).thenReturn(false);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, APPROVED_AT);

        service.registerConfirmedPayment(event);

        ArgumentCaptor<SettlementItem> captor = ArgumentCaptor.forClass(SettlementItem.class);
        verify(itemRepository).save(captor.capture());
        SettlementItem item = captor.getValue();
        assertThat(item.getPaymentId()).isEqualTo(100L);
        assertThat(item.getOrderNo()).isEqualTo("order-1");
        assertThat(item.getAmount()).isEqualTo(10_000);
        assertThat(item.getConfirmedDate()).isEqualTo(DATE);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("같은 결제가 두 번 와도 정산 항목은 한 번만 적재 (멱등)")
    void idempotentOnDuplicatePayment() {
        when(itemRepository.existsByPaymentId(100L)).thenReturn(true);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, APPROVED_AT);

        service.registerConfirmedPayment(event);

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("에스크로 릴리스: 항목을 CONFIRMED로 전이하고 saveAndFlush 한다")
    void confirmSettlementTransitionsToConfirmed() {
        SettlementItem item = SettlementItem.of(1L, "order-1", 10_000, DATE);
        when(itemRepository.findByOrderNo("order-1")).thenReturn(Optional.of(item));

        service.confirmSettlement("order-1");

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("에스크로 릴리스: 항목이 없으면(순서 레이스) 무시하고 저장하지 않는다")
    void confirmSettlementMissingItemIsIgnored() {
        when(itemRepository.findByOrderNo("order-x")).thenReturn(Optional.empty());

        service.confirmSettlement("order-x");

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("정산 배치: CONFIRMED만 합계 → 3% 수수료(내림) → net 저장 + 항목 SETTLED")
    void settleAggregatesFeeAndMarksItems() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        SettlementItem item1 = confirmedItem(1L, "order-1", 10_000);
        SettlementItem item2 = confirmedItem(2L, "order-2", 20_000);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(item1, item2));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settlement = service.settle(DATE);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(captor.capture());
        Settlement saved = captor.getValue();
        assertThat(saved.getGrossAmount()).isEqualTo(30_000);   // 10,000 + 20,000
        assertThat(saved.getFeeAmount()).isEqualTo(900);        // 30,000 * 3% = 900
        assertThat(saved.getNetAmount()).isEqualTo(29_100);     // 30,000 - 900
        assertThat(saved.getItemCount()).isEqualTo(2);
        assertThat(saved.getSettlementDate()).isEqualTo(DATE);
        assertThat(settlement).isSameAs(saved);

        // 집계된 항목은 SETTLED로 전이
        assertThat(item1.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
        assertThat(item2.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("정산 배치는 CONFIRMED만 조회한다 — PENDING_CONFIRMATION(구매확정 전)은 집계 제외")
    void settleQueriesOnlyConfirmed() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        SettlementItem confirmed = confirmedItem(1L, "order-1", 10_000);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(confirmed));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.settle(DATE);

        // 배치는 CONFIRMED 상태만 조회 대상으로 삼는다(보류 실현)
        verify(itemRepository).findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE);
        verify(itemRepository, never())
                .findByStatusAndConfirmedDate(eq(SettlementItemStatus.PENDING_CONFIRMATION), any());
    }

    @Test
    @DisplayName("이미 정산된 날짜 재실행: 아무 것도 하지 않는다 (배치 멱등)")
    void idempotentOnAlreadySettledDate() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(true);

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(itemRepository, never()).findByStatusAndConfirmedDate(any(), any());
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("집계 대상 CONFIRMED 항목이 없으면 빈 정산을 만들지 않는다")
    void noItemsProducesNoSettlement() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of());

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("전액취소: 항목을 CANCELED로 제외하고 saveAndFlush")
    void reflectCancellationFullyCancels() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, true);

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("부분취소: 정산 금액을 차감하고 상태는 유지, saveAndFlush")
    void reflectCancellationPartiallyReduces() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 3_000, false);

        service.reflectCancellation(event);

        assertThat(item.getAmount()).isEqualTo(7_000);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("SETTLED 후 취소: 항목 미변경 + postsettle 카운터 증가(사후 조정 대상)")
    void reflectCancellationAfterSettledCountsOnly() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.markSettled(); // CONFIRMED → SETTLED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, true);

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.SETTLED); // 미변경
        assertThat(item.getAmount()).isEqualTo(10_000);                       // 미변경
        verify(itemRepository, never()).saveAndFlush(any());
        assertThat(meterRegistry.counter("settlement.postsettle.cancel").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("취소: 정산에 없는 결제면 무시")
    void reflectCancellationMissingItemIsIgnored() {
        when(itemRepository.findByPaymentId(999L)).thenReturn(Optional.empty());
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-x", 999L, 10_000, true);

        service.reflectCancellation(event);

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("취소: 이미 CANCELED면 멱등하게 무시")
    void reflectCancellationIdempotentOnAlreadyCanceled() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.cancel(); // → CANCELED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, true);

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository, never()).saveAndFlush(any());
    }
}
