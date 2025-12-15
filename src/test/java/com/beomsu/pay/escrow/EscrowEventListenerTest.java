package com.beomsu.pay.escrow;

import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.*;

class EscrowEventListenerTest {

    private EscrowService escrowService;
    private EscrowEventListener listener;

    @BeforeEach
    void setUp() {
        escrowService = mock(EscrowService.class);
        listener = new EscrowEventListener(escrowService);
    }

    @Test
    @DisplayName("PaymentConfirmedEvent → escrowService.hold 위임")
    void onConfirmedHolds() {
        Instant approvedAt = Instant.parse("2026-07-01T00:00:00Z");
        listener.onConfirmed(new PaymentConfirmedEvent("ord-1", 10L, 20_000, approvedAt));

        verify(escrowService).hold("ord-1", 20_000, approvedAt);
    }

    @Test
    @DisplayName("전액취소 PaymentCanceledEvent → escrowService.refundIfHeld 위임")
    void onFullCancelRefunds() {
        listener.onCanceled(new PaymentCanceledEvent("ord-1", 10L, 20_000, true));

        verify(escrowService).refundIfHeld("ord-1");
    }

    @Test
    @DisplayName("부분취소 PaymentCanceledEvent → 홀드 유지(refundIfHeld 미호출)")
    void onPartialCancelKeepsHold() {
        listener.onCanceled(new PaymentCanceledEvent("ord-1", 10L, 3_000, false));

        verify(escrowService, never()).refundIfHeld(anyString());
    }
}
