package com.beomsu.paynotification;

import com.beomsu.paycontracts.PayTopics;
import com.beomsu.paycontracts.PaymentConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 이벤트 Kafka 리스너 — 모놀리스의 {@code @ApplicationModuleListener} 구독을
 * 프로세스 밖에서 이어받는다.
 *
 * <p>실패의 두 층위를 구분한다:
 * <ul>
 *   <li><b>발송 실패</b>(외부 채널 오류 등) — {@link NotificationService}가 삼켜서 자기 DLQ
 *       테이블(DeadLetter)에 격리한다. 리스너는 정상 종료해 오프셋이 진행된다.
 *   <li><b>역직렬화 실패</b>(poison message) — 서비스에 넣을 수조차 없으므로 예외를 던져
 *       {@link KafkaConsumerConfig}의 재시도→DLT(-dlt) 경로에 태운다.
 * </ul>
 */
@Component
@RequiredArgsConstructor
class NotificationEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = PayTopics.PAYMENT_CONFIRMED)
    void onConfirmed(ConsumerRecord<String, String> rec) {
        PaymentConfirmedEvent event;
        try {
            event = objectMapper.readValue(rec.value(), PaymentConfirmedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "이벤트 역직렬화 실패 topic=%s partition=%d offset=%d"
                            .formatted(rec.topic(), rec.partition(), rec.offset()), e);
        }
        notificationService.handlePaymentConfirmed(event);
    }
}
