package com.beomsu.pay.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationAdminServiceTest {

    private DeadLetterRepository deadLetters;
    private ProcessedEventRepository processedEvents;
    private NotificationSender sender;
    private NotificationAdminService service;

    @BeforeEach
    void setUp() {
        deadLetters = mock(DeadLetterRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        sender = mock(NotificationSender.class);
        service = new NotificationAdminService(deadLetters, processedEvents, sender);
    }

    private DeadLetter deadLetter() {
        return DeadLetter.of("PaymentConfirmedEvent", "payment-confirmed-100",
                "order-1", 100L, 10_000, "발송 채널 장애");
    }

    @Test
    @DisplayName("재처리 성공: 알림 재발송 + 처리완료 마킹 + DLQ에서 제거")
    void reprocessSuccess() {
        DeadLetter dl = deadLetter();
        when(deadLetters.findById(1L)).thenReturn(Optional.of(dl));

        boolean ok = service.reprocess(1L);

        assertThat(ok).isTrue();
        verify(sender).sendPaymentReceipt("order-1", 100L, 10_000);
        verify(processedEvents).save(any(ProcessedEvent.class));
        verify(deadLetters).delete(dl);
    }

    @Test
    @DisplayName("재처리 재실패: DLQ에 남기고 재시도 횟수만 증가")
    void reprocessStillFails() {
        DeadLetter dl = deadLetter();
        when(deadLetters.findById(1L)).thenReturn(Optional.of(dl));
        doThrow(new RuntimeException("여전히 장애"))
                .when(sender).sendPaymentReceipt(anyString(), anyLong(), anyLong());

        boolean ok = service.reprocess(1L);

        assertThat(ok).isFalse();
        assertThat(dl.getRetryCount()).isEqualTo(1);
        verify(deadLetters, never()).delete(any());
        verify(processedEvents, never()).save(any());
    }
}
