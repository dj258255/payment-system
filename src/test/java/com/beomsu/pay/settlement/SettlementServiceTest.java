package com.beomsu.pay.settlement;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementServiceTest {

    private SettlementItemRepository itemRepository;
    private SettlementRepository settlementRepository;
    private SettlementService service;

    private static final Instant APPROVED_AT = Instant.parse("2026-07-05T09:00:00Z");
    private static final LocalDate DATE = LocalDate.ofInstant(APPROVED_AT, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        itemRepository = mock(SettlementItemRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        service = new SettlementService(itemRepository, settlementRepository);
    }

    @Test
    @DisplayName("결제 승인: 정산 항목을 PENDING으로 적재한다 (confirmedDate는 승인 시각 UTC)")
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
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
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
    @DisplayName("정산 배치: PENDING 합계 → 3% 수수료(내림) → net 계산해 저장 + 항목 SETTLED")
    void settleAggregatesFeeAndMarksItems() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        SettlementItem item1 = SettlementItem.of(1L, "order-1", 10_000, DATE);
        SettlementItem item2 = SettlementItem.of(2L, "order-2", 20_000, DATE);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.PENDING, DATE))
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
    @DisplayName("이미 정산된 날짜 재실행: 아무 것도 하지 않는다 (배치 멱등)")
    void idempotentOnAlreadySettledDate() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(true);

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(itemRepository, never()).findByStatusAndConfirmedDate(any(), any());
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("집계 대상 PENDING 항목이 없으면 빈 정산을 만들지 않는다")
    void noItemsProducesNoSettlement() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.PENDING, DATE))
                .thenReturn(List.of());

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
    }
}
