package com.beomsu.pay.reconciliation;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReconciliationServiceTest {

    private InternalRecordRepository internalRecords;
    private ReconciliationResultRepository results;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        internalRecords = mock(InternalRecordRepository.class);
        results = mock(ReconciliationResultRepository.class);
        service = new ReconciliationService(internalRecords, results);
    }

    @Test
    @DisplayName("대사 매칭 엔진: 4분류를 정확히 낸다 (MATCHED/AMOUNT_MISMATCH/INTERNAL_ONLY/EXTERNAL_ONLY)")
    void reconcileClassifiesFourCases() {
        // 내부 {A:1000, B:2000, C:3000}
        when(internalRecords.findAll()).thenReturn(List.of(
                InternalRecord.of("A", 1000),
                InternalRecord.of("B", 2000),
                InternalRecord.of("C", 3000)));
        // 외부 {A:1000, B:2500, D:4000}
        List<ExternalRecord> external = List.of(
                new ExternalRecord("A", 1000),
                new ExternalRecord("B", 2500),
                new ExternalRecord("D", 4000));

        List<ReconciliationResult> reconciled = service.reconcile(external);

        Map<String, ReconciliationResult> byOrder = reconciled.stream()
                .collect(Collectors.toMap(ReconciliationResult::getOrderNo, Function.identity()));

        // A: 양쪽 일치 → MATCHED / AUTO_RESOLVED
        ReconciliationResult a = byOrder.get("A");
        assertThat(a.getResult()).isEqualTo(ReconResultType.MATCHED);
        assertThat(a.getStatus()).isEqualTo(ReconStatus.AUTO_RESOLVED);
        assertThat(a.getInternalAmount()).isEqualTo(1000);
        assertThat(a.getExternalAmount()).isEqualTo(1000);

        // B: 금액 불일치(2000 vs 2500) → AMOUNT_MISMATCH / PENDING
        ReconciliationResult b = byOrder.get("B");
        assertThat(b.getResult()).isEqualTo(ReconResultType.AMOUNT_MISMATCH);
        assertThat(b.getStatus()).isEqualTo(ReconStatus.PENDING);
        assertThat(b.getInternalAmount()).isEqualTo(2000);
        assertThat(b.getExternalAmount()).isEqualTo(2500);

        // C: 내부에만 → INTERNAL_ONLY / PENDING
        ReconciliationResult c = byOrder.get("C");
        assertThat(c.getResult()).isEqualTo(ReconResultType.INTERNAL_ONLY);
        assertThat(c.getStatus()).isEqualTo(ReconStatus.PENDING);
        assertThat(c.getInternalAmount()).isEqualTo(3000);
        assertThat(c.getExternalAmount()).isNull();

        // D: 외부에만 → EXTERNAL_ONLY / PENDING
        ReconciliationResult d = byOrder.get("D");
        assertThat(d.getResult()).isEqualTo(ReconResultType.EXTERNAL_ONLY);
        assertThat(d.getStatus()).isEqualTo(ReconStatus.PENDING);
        assertThat(d.getInternalAmount()).isNull();
        assertThat(d.getExternalAmount()).isEqualTo(4000);

        assertThat(reconciled).hasSize(4);
        verify(results).saveAll(anyList());
    }

    @Test
    @DisplayName("매칭 엔진은 결정적: 같은 입력이면 결과 순서·내용이 동일하다")
    void reconcileIsDeterministic() {
        when(internalRecords.findAll()).thenReturn(List.of(
                InternalRecord.of("B", 2000),
                InternalRecord.of("A", 1000)));
        List<ExternalRecord> external = List.of(
                new ExternalRecord("A", 1000),
                new ExternalRecord("B", 2000));

        List<String> first = service.reconcile(external).stream()
                .map(ReconciliationResult::getOrderNo).toList();
        List<String> second = service.reconcile(external).stream()
                .map(ReconciliationResult::getOrderNo).toList();

        assertThat(first).containsExactly("A", "B"); // orderNo 정렬 순서
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("결제 승인: 내부 기록을 적재한다")
    void recordInternalSavesRecord() {
        when(internalRecords.existsByOrderNo("order-1")).thenReturn(false);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

        service.recordInternal(event);

        verify(internalRecords).save(any(InternalRecord.class));
    }

    @Test
    @DisplayName("같은 주문이 두 번 와도 내부 기록은 한 번만 (멱등)")
    void recordInternalIdempotent() {
        when(internalRecords.existsByOrderNo("order-1")).thenReturn(true);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

        service.recordInternal(event);

        verify(internalRecords, never()).save(any());
    }
}
