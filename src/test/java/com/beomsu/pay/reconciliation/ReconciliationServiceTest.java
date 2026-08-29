package com.beomsu.pay.reconciliation;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

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
    @DisplayName("대사는 그 거래일만 본다 — 전체를 비교하면 지난 날짜가 전부 불일치로 쏟아진다")
    void reconcileIsScopedToOneTradeDate() {
        LocalDate target = LocalDate.of(2026, 7, 5);
        // 그 날짜의 내부 기록만 조회된다. 지난 날짜(7/4)는 이 조회에 애초에 들어오지 않는다.
        when(internalRecords.findByTradeDate(target)).thenReturn(List.of(
                InternalRecord.of("today-1", 1000, Instant.parse("2026-07-05T05:00:00Z"))));

        List<ReconciliationResult> reconciled =
                service.reconcile(target, List.of(new ExternalRecord("today-1", 1000)));

        assertThat(reconciled).hasSize(1);
        assertThat(reconciled.get(0).getResult()).isEqualTo(ReconResultType.MATCHED);
        verify(internalRecords, never()).findAll();
        verify(internalRecords).findByTradeDate(target);
    }

    @Test
    @DisplayName("같은 거래일을 다시 대사하면 이전 판정을 갈아끼운다 — 두 번 눌러도 예외 큐가 안 늘어난다")
    void rerunReplacesPreviousResultsForThatDate() {
        LocalDate target = LocalDate.of(2026, 7, 5);
        when(internalRecords.findByTradeDate(target)).thenReturn(List.of(
                InternalRecord.of("A", 1000, Instant.parse("2026-07-05T05:00:00Z"))));

        service.reconcile(target, List.of(new ExternalRecord("A", 1000)));

        verify(results).deleteByTradeDate(target);
    }

    @Test
    @DisplayName("대사 매칭 엔진: 4분류를 정확히 낸다 (MATCHED/AMOUNT_MISMATCH/INTERNAL_ONLY/EXTERNAL_ONLY)")
    void reconcileClassifiesFourCases() {
        // 내부 {A:1000, B:2000, C:3000}
        when(internalRecords.findByTradeDate(LocalDate.of(2026, 7, 5))).thenReturn(List.of(
                InternalRecord.of("A", 1000, Instant.parse("2026-07-05T05:00:00Z")),
                InternalRecord.of("B", 2000, Instant.parse("2026-07-05T05:00:00Z")),
                InternalRecord.of("C", 3000, Instant.parse("2026-07-05T05:00:00Z"))));
        // 외부 {A:1000, B:2500, D:4000}
        List<ExternalRecord> external = List.of(
                new ExternalRecord("A", 1000),
                new ExternalRecord("B", 2500),
                new ExternalRecord("D", 4000));

        List<ReconciliationResult> reconciled = service.reconcile(LocalDate.of(2026, 7, 5), external);

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
        when(internalRecords.findByTradeDate(LocalDate.of(2026, 7, 5))).thenReturn(List.of(
                InternalRecord.of("B", 2000, Instant.parse("2026-07-05T05:00:00Z")),
                InternalRecord.of("A", 1000, Instant.parse("2026-07-05T05:00:00Z"))));
        List<ExternalRecord> external = List.of(
                new ExternalRecord("A", 1000),
                new ExternalRecord("B", 2000));

        List<String> first = service.reconcile(LocalDate.of(2026, 7, 5), external).stream()
                .map(ReconciliationResult::getOrderNo).toList();
        List<String> second = service.reconcile(LocalDate.of(2026, 7, 5), external).stream()
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

    @Test
    @DisplayName("같은 주문이 여러 행으로 오면 합산한다 — 승인·환불이 별도 행인 것은 정상이다")
    void multipleExternalRowsForSameOrderAreSummed() {
        LocalDate target = LocalDate.of(2026, 8, 30);
        // 내부는 부분취소 후 잔여 7,000
        when(internalRecords.findByTradeDate(target)).thenReturn(List.of(
                InternalRecord.of("ord-1", 7_000, Instant.parse("2026-08-30T05:00:00Z"))));

        // PG 파일은 승인 10,000과 환불 -3,000을 <별도 행>으로 준다.
        // 주요 PG가 환불·챠지백을 별도 journal type으로 내면서 같은 참조번호를 공유하기 때문이다.
        List<ReconciliationResult> reconciled = service.reconcile(target, List.of(
                new ExternalRecord("ord-1", 10_000),
                new ExternalRecord("ord-1", -3_000)));

        assertThat(reconciled).hasSize(1);
        assertThat(reconciled.get(0).getResult())
                .as("합산하면 7,000이라 내부 잔여와 맞는다. 덮어쓰면 -3,000으로 읽혀 없는 불일치가 생긴다")
                .isEqualTo(ReconResultType.MATCHED);
        assertThat(reconciled.get(0).getExternalAmount()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("합산 결과가 내부와 다르면 그때는 진짜 불일치다")
    void summedRowsStillDetectRealMismatch() {
        LocalDate target = LocalDate.of(2026, 8, 30);
        when(internalRecords.findByTradeDate(target)).thenReturn(List.of(
                InternalRecord.of("ord-1", 10_000, Instant.parse("2026-08-30T05:00:00Z"))));

        List<ReconciliationResult> reconciled = service.reconcile(target, List.of(
                new ExternalRecord("ord-1", 9_000),
                new ExternalRecord("ord-1", -270)));   // 합계 8,730

        assertThat(reconciled.get(0).getResult()).isEqualTo(ReconResultType.AMOUNT_MISMATCH);
        assertThat(reconciled.get(0).getExternalAmount()).isEqualTo(8_730);
    }
}
