package com.beomsu.paysettlement;

import com.beomsu.paycontracts.PayTopics;
import com.beomsu.paycontracts.PaymentCanceledEvent;
import com.beomsu.paycontracts.PaymentConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kafka 리스너의 역직렬화·위임 계약 단위 테스트 — 브로커 없이 ConsumerRecord를 직접 만든다.
 *
 * <p>와이어 페이로드는 발행측(Modulith 외부화)의 Jackson 직렬화 결과와 같은 JSON이다.
 * 계약 record의 필드명이 곧 스키마이므로, 여기서 JSON 키를 하드코딩해 <b>발행측이 필드명을
 * 바꾸면 이 테스트가 깨지게</b> 한다(계약 회귀 방지).
 */
class SettlementEventListenerTest {

    private SettlementService settlementService;
    private SettlementEventListener listener;

    @BeforeEach
    void setUp() {
        settlementService = mock(SettlementService.class);
        listener = new SettlementEventListener(settlementService, new ObjectMapper()
                .findAndRegisterModules());
    }

    private static ConsumerRecord<String, String> record(String topic, String json) {
        return new ConsumerRecord<>(topic, 0, 0L, "ORD-1", json);
    }

    @Test
    @DisplayName("payment.confirmed JSON을 계약 record로 역직렬화해 적재를 위임한다")
    void confirmedDelegates() {
        listener.onConfirmed(record(PayTopics.PAYMENT_CONFIRMED,
                "{\"orderNo\":\"ORD-1\",\"paymentId\":10,\"amount\":10000,\"approvedAt\":\"2026-08-01T09:00:00Z\"}"));

        ArgumentCaptor<PaymentConfirmedEvent> captor = ArgumentCaptor.forClass(PaymentConfirmedEvent.class);
        verify(settlementService).registerConfirmedPayment(captor.capture());
        assertThat(captor.getValue().orderNo()).isEqualTo("ORD-1");
        assertThat(captor.getValue().amount()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("escrow.released는 릴리스 시각의 UTC 날짜로 확정을 위임한다")
    void escrowReleasedDelegatesWithUtcDate() {
        listener.onEscrowReleased(record(PayTopics.ESCROW_RELEASED,
                "{\"orderNo\":\"ORD-1\",\"amount\":10000,\"releasedAt\":\"2026-08-01T23:30:00Z\"}"));

        verify(settlementService).confirmSettlement("ORD-1", LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("payment.canceled의 절대 잔액(settleableBalance)이 그대로 전달된다")
    void canceledDelegates() {
        listener.onCanceled(record(PayTopics.PAYMENT_CANCELED,
                "{\"orderNo\":\"ORD-1\",\"paymentId\":10,\"cancelAmount\":3000,"
                        + "\"settleableBalance\":7000,\"fullyCanceled\":false}"));

        ArgumentCaptor<PaymentCanceledEvent> captor = ArgumentCaptor.forClass(PaymentCanceledEvent.class);
        verify(settlementService).reflectCancellation(captor.capture());
        assertThat(captor.getValue().settleableBalance()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("깨진 JSON은 예외를 던져 에러 핸들러(재시도→DLT) 경로로 보낸다 — 조용히 삼키지 않는다")
    void poisonMessageThrows() {
        assertThatThrownBy(() ->
                listener.onConfirmed(record(PayTopics.PAYMENT_CONFIRMED, "not-json{{")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PayTopics.PAYMENT_CONFIRMED);
        verify(settlementService, never()).registerConfirmedPayment(any());
    }
}
