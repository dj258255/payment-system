package com.beomsu.paynotification;

import com.beomsu.paycontracts.PayTopics;
import com.beomsu.paycontracts.PaymentConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Kafka 리스너의 역직렬화·위임 계약 단위 테스트 — settlement-service의 리스너 테스트와 같은 패턴. */
class NotificationEventListenerTest {

    private NotificationService notificationService;
    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        listener = new NotificationEventListener(notificationService,
                new ObjectMapper().findAndRegisterModules());
    }

    private static ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>(PayTopics.PAYMENT_CONFIRMED, 0, 0L, "ORD-1", json);
    }

    @Test
    @DisplayName("payment.confirmed JSON을 계약 record로 역직렬화해 발송 처리에 위임한다")
    void confirmedDelegates() {
        listener.onConfirmed(record(
                "{\"orderNo\":\"ORD-1\",\"paymentId\":10,\"amount\":10000,\"approvedAt\":\"2026-08-01T09:00:00Z\"}"));

        ArgumentCaptor<PaymentConfirmedEvent> captor = ArgumentCaptor.forClass(PaymentConfirmedEvent.class);
        verify(notificationService).handlePaymentConfirmed(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("깨진 JSON은 예외를 던져 재시도→DLT 경로로 보낸다 — 발송 DLQ와 층위가 다르다")
    void poisonMessageThrows() {
        assertThatThrownBy(() -> listener.onConfirmed(record("broken{{")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationService, never()).handlePaymentConfirmed(any());
    }
}
