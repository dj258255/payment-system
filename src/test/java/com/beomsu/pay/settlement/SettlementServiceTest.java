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
        // feeBps=270(2.7%), payoutDays=2 — application.yml 기본값과 동일하게 주입.
        service = new SettlementService(itemRepository, settlementRepository, meterRegistry, 270L, 2);
    }

    /** CONFIRMED 상태의 항목을 만든다(승인·구매확정이 같은 날 DATE인 경우 — confirmedDate=DATE). */
    private static SettlementItem confirmedItem(long paymentId, String orderNo, long amount) {
        SettlementItem item = SettlementItem.of(paymentId, orderNo, amount, DATE);
        item.confirm(DATE);
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
    @DisplayName("에스크로 릴리스: 항목을 CONFIRMED로 전이하고 confirmedDate를 릴리스일로 재스탬프 후 saveAndFlush")
    void confirmSettlementTransitionsAndRestampsDate() {
        // 승인일(DATE)에 적재된 항목이 며칠 뒤 릴리스된다 — 실제 에스크로 홀드 경로.
        SettlementItem item = SettlementItem.of(1L, "order-1", 10_000, DATE);
        LocalDate releaseDate = DATE.plusDays(7); // 기본 7일 홀드 후 릴리스
        when(itemRepository.findByOrderNo("order-1")).thenReturn(Optional.of(item));

        service.confirmSettlement("order-1", releaseDate);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        // 핵심: 집계 기준일이 승인일(DATE)이 아니라 릴리스일로 재스탬프돼야 한다.
        assertThat(item.getConfirmedDate()).isEqualTo(releaseDate);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("지연 구매확정 회귀: 승인일 배치는 이 항목을 못 잡고, 릴리스일 배치가 잡는다(영구 미정산 방지)")
    void delayedConfirmationIsSettledOnReleaseDateNotApprovalDate() {
        // 승인일 D에 적재(PENDING) → D+7 릴리스로 CONFIRMED. confirmedDate는 D+7로 재스탬프돼야 한다.
        SettlementItem item = SettlementItem.of(1L, "order-1", 10_000, DATE);
        LocalDate releaseDate = DATE.plusDays(7);
        when(itemRepository.findByOrderNo("order-1")).thenReturn(Optional.of(item));
        service.confirmSettlement("order-1", releaseDate);

        // 릴리스일 배치가 이 항목을 집계 대상으로 조회한다(승인일이 아니라).
        when(settlementRepository.existsBySettlementDate(releaseDate)).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, releaseDate))
                .thenReturn(List.of(item));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settled = service.settle(releaseDate);

        assertThat(settled).isNotNull();
        assertThat(settled.getSettlementDate()).isEqualTo(releaseDate);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
        // 승인일(DATE) 배치엔 이 항목이 confirmedDate로 걸리지 않는다(재스탬프됐으므로).
        assertThat(item.getConfirmedDate()).isNotEqualTo(DATE);
    }

    @Test
    @DisplayName("에스크로 릴리스: 항목이 없으면(순서 레이스) 무시하고 저장하지 않는다")
    void confirmSettlementMissingItemIsIgnored() {
        when(itemRepository.findByOrderNo("order-x")).thenReturn(Optional.empty());

        service.confirmSettlement("order-x", DATE);

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("정산 배치: CONFIRMED만 합계 → 2.7% 수수료+부가세(내림) → net 저장 + 항목 SETTLED")
    void settleAggregatesFeeAndMarksItems() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        SettlementItem item1 = confirmedItem(1L, "order-1", 40_000);
        SettlementItem item2 = confirmedItem(2L, "order-2", 60_000);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(item1, item2));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settlement = service.settle(DATE);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(captor.capture());
        Settlement saved = captor.getValue();
        // 검산: gross=100,000, feeBps=270 → fee=2700, feeVat=270, net=97030
        assertThat(saved.getGrossAmount()).isEqualTo(100_000);  // 40,000 + 60,000
        assertThat(saved.getFeeAmount()).isEqualTo(2_700);      // 100,000 * 270 / 10000
        assertThat(saved.getFeeVatAmount()).isEqualTo(270);     // fee / 10
        assertThat(saved.getNetAmount()).isEqualTo(97_030);     // gross - fee - feeVat
        assertThat(saved.getItemCount()).isEqualTo(2);
        assertThat(saved.getSettlementDate()).isEqualTo(DATE);
        // 지급예정일 = 정산일 + 2영업일. DATE(2026-07-05, 일요일) → 화(07-07)
        assertThat(saved.getPayoutDate()).isEqualTo(BusinessDays.plusBusinessDays(DATE, 2));
        assertThat(settlement).isSameAs(saved);

        // 집계된 항목은 SETTLED로 전이
        assertThat(item1.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
        assertThat(item2.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("수수료 검산: gross=100,000 → fee 2700, feeVat 270, net 97030")
    void settleFeeModelExactValues() {
        when(settlementRepository.existsBySettlementDate(DATE)).thenReturn(false);
        SettlementItem item = confirmedItem(1L, "order-1", 100_000);
        when(itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(item));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement saved = service.settle(DATE);

        assertThat(saved.getGrossAmount()).isEqualTo(100_000);
        assertThat(saved.getFeeAmount()).isEqualTo(2_700);
        assertThat(saved.getFeeVatAmount()).isEqualTo(270);
        assertThat(saved.getNetAmount()).isEqualTo(97_030);
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
    @DisplayName("전액취소: 항목을 CANCELED로 제외하고 saveAndFlush (잔액 0)")
    void reflectCancellationFullyCancels() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, 0, true);

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("부분취소: 정산 금액을 취소 후 잔액(절대값)으로 세팅하고 상태 유지, saveAndFlush")
    void reflectCancellationSetsSettleableBalance() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        // 3,000 취소 후 잔액 7,000
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 3_000, 7_000, false);

        service.reflectCancellation(event);

        assertThat(item.getAmount()).isEqualTo(7_000);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("부분취소 멱등: 같은 취소 이벤트가 중복 배달돼도 잔액으로 세팅하므로 이중 차감되지 않는다")
    void reflectCancellationIsIdempotentOnRedelivery() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 3_000, 7_000, false);

        service.reflectCancellation(event); // 1차
        service.reflectCancellation(event); // 재배달(at-least-once)

        // 델타 차감이면 4,000이 됐겠지만, 절대 잔액 세팅이라 7,000 유지 — 이중 차감 없음.
        assertThat(item.getAmount()).isEqualTo(7_000);
        verify(itemRepository, times(2)).saveAndFlush(item);
    }

    @Test
    @DisplayName("SETTLED 후 취소: 항목 미변경 + postsettle 카운터 증가(사후 조정 대상)")
    void reflectCancellationAfterSettledCountsOnly() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.markSettled(); // CONFIRMED → SETTLED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, 0, true);

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
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-x", 999L, 10_000, 0, true);

        service.reflectCancellation(event);

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("취소: 이미 CANCELED면 멱등하게 무시")
    void reflectCancellationIdempotentOnAlreadyCanceled() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.cancel(); // → CANCELED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 10_000, 0, true);

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository, never()).saveAndFlush(any());
    }
}
