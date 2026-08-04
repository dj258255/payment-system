package com.beomsu.pay.fraud;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import com.beomsu.pay.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FraudPostHocListenerTest {

    private PaymentService paymentService;
    private FraudService fraudService;
    private FraudReviewRepository reviewRepository;
    private FraudPostHocListener listener;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        fraudService = mock(FraudService.class);
        reviewRepository = mock(FraudReviewRepository.class);
        listener = new FraudPostHocListener(paymentService, fraudService, reviewRepository);
    }

    private PaymentConfirmedEvent event() {
        return new PaymentConfirmedEvent("ord-1", 10L, 50_000, Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    @DisplayName("REVIEW 판정 → 심사 큐에 적재(orderNo/cardKey/amount 매핑)")
    void reviewFlagsQueue() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));

        listener.onConfirmed(event());

        ArgumentCaptor<FraudReview> captor = ArgumentCaptor.forClass(FraudReview.class);
        verify(reviewRepository).save(captor.capture());
        FraudReview saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo("ord-1");
        assertThat(saved.getPaymentId()).isEqualTo(10L);
        assertThat(saved.getCardKey()).isEqualTo("card-xyz");
        assertThat(saved.getAmount()).isEqualTo(50_000);
        assertThat(saved.getDecision()).isEqualTo(FdsDecision.REVIEW);
        assertThat(saved.getStatus()).isEqualTo(FraudReviewStatus.PENDING);
        assertThat(saved.getReasons()).isEqualTo("HIGH_AMOUNT");
    }

    @Test
    @DisplayName("BLOCK 판정 → 긴급 심사 대상으로 적재")
    void blockFlagsQueue() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(120, FdsDecision.BLOCK, List.of("BLACKLISTED_CARD")));

        listener.onConfirmed(event());

        verify(reviewRepository).save(any(FraudReview.class));
    }

    @Test
    @DisplayName("ALLOW 판정 → 큐에 적재하지 않음")
    void allowDoesNotFlag() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));

        listener.onConfirmed(event());

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("CHALLENGE 판정 → 큐에 적재하지 않음")
    void challengeDoesNotFlag() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(40, FdsDecision.CHALLENGE, List.of("VELOCITY_EXCEEDED(6)")));

        listener.onConfirmed(event());

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("paymentKey 조회 empty → 아무 것도 하지 않음(평가/적재 skip)")
    void missingPaymentKeySkips() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.empty());

        listener.onConfirmed(event());

        verify(fraudService, never()).evaluate(any());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("사후 재평가는 cardKey+amount로 평가한다(ip/device/userId는 0/null)")
    void evaluatesWithCardKeyAndAmount() {
        when(paymentService.paymentKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));

        listener.onConfirmed(event());

        ArgumentCaptor<FraudCheckRequest> captor = ArgumentCaptor.forClass(FraudCheckRequest.class);
        verify(fraudService).evaluate(captor.capture());
        FraudCheckRequest req = captor.getValue();
        assertThat(req.cardKey()).isEqualTo("card-xyz");
        assertThat(req.amount()).isEqualTo(50_000);
        assertThat(req.userId()).isEqualTo(0L);
        assertThat(req.ip()).isNull();
        assertThat(req.deviceId()).isNull();
    }
}
