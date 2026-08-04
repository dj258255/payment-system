package com.beomsu.pay.ledger;

import com.beomsu.pay.dispute.DisputeLostEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private LedgerTransactionRepository repository;
    private LedgerService service;

    private final PaymentConfirmedEvent event =
            new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

    @BeforeEach
    void setUp() {
        repository = mock(LedgerTransactionRepository.class);
        service = new LedgerService(repository);
    }

    @Test
    @DisplayName("최근 원장 조회: 트랜잭션을 뷰로 매핑하고 균형(balanced) 여부를 계산한다")
    void recentTransactionsMapsToViewWithBalance() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong()))
                .thenReturn(false);
        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        service.recordPaymentConfirmed(event);
        verify(repository).save(captor.capture());
        when(repository.findTop50ByOrderByIdDesc()).thenReturn(List.of(captor.getValue()));

        List<LedgerView> views = service.recentTransactions();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).balanced()).isTrue();        // 차변=대변
        assertThat(views.get(0).entries()).hasSize(2);
        assertThat(views.get(0).txType()).isEqualTo("PAYMENT_APPROVED");
    }

    @Test
    @DisplayName("결제 승인: PG미수금(차변) ↔ 매출(대변), 균형 잡힌 분개 저장")
    void recordsBalancedEntriesOnConfirm() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong()))
                .thenReturn(false);

        service.recordPaymentConfirmed(event);

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        LedgerTransaction tx = captor.getValue();
        assertThat(tx.imbalance()).isZero();                 // 차변=대변
        assertThat(tx.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("같은 결제 이벤트가 두 번 와도 분개는 한 번만 (멱등)")
    void idempotentOnDuplicateEvent() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceId("PAYMENT_APPROVED", "PAYMENT", 100L))
                .thenReturn(true);

        service.recordPaymentConfirmed(event);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("분쟁 패소: 매출(차변) ↔ PG미수금(대변) 역분개, 균형 잡힌 분개 저장")
    void recordsBalancedReversalOnDisputeLost() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong()))
                .thenReturn(false);

        service.recordDisputeLost(new DisputeLostEvent("order-1", 100L, 10_000, 42L));

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        LedgerTransaction tx = captor.getValue();
        assertThat(tx.imbalance()).isZero();                 // 차변=대변
        assertThat(tx.getEntries()).hasSize(2);
        assertThat(tx.getTxType()).isEqualTo("DISPUTE_LOST");
        assertThat(tx.getSourceType()).isEqualTo("DISPUTE");
        assertThat(tx.getSourceId()).isEqualTo(42L);
        // 역분개 방향: SALES 차변, PG_RECEIVABLE 대변(결제취소와 동일)
        LedgerEntry sales = tx.getEntries().stream()
                .filter(e -> e.getAccount() == AccountType.SALES).findFirst().orElseThrow();
        assertThat(sales.getDirection()).isEqualTo(EntryDirection.DEBIT);
    }

    @Test
    @DisplayName("같은 분쟁 패소 이벤트가 두 번 와도 역분개는 한 번만 (멱등, disputeId 기준)")
    void idempotentOnDuplicateDisputeLost() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceId("DISPUTE_LOST", "DISPUTE", 42L))
                .thenReturn(true);

        service.recordDisputeLost(new DisputeLostEvent("order-1", 100L, 10_000, 42L));

        verify(repository, never()).save(any());
    }
}
