package com.beomsu.pay.ledger;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

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
}
