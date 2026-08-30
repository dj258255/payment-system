package com.beomsu.paysettlement;

import com.beomsu.paycontracts.EscrowReleasedEvent;
import com.beomsu.paycontracts.PayTopics;
import com.beomsu.paycontracts.PaymentCanceledEvent;
import com.beomsu.paycontracts.PaymentConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 결제·에스크로 이벤트 Kafka 리스너 — 모놀리스의 {@code @ApplicationModuleListener}가 하던
 * 구독을 프로세스 밖에서 이어받는다. 도메인 반영 로직({@link SettlementService})은 무수정이다.
 *
 * <p><b>at-least-once + 멱등</b>: 발행측 Outbox 재발행으로 중복 수신이 가능하다. 서비스가
 * 이미 상태 가드로 멱등하게 짜여 있다(적재는 existsByPaymentId, 부분취소는 절대값 세팅,
 * 전이는 상태 가드). 인프로세스 시절의 방어가 그대로 Kafka 방어가 된다.
 *
 * <p><b>순서</b>: 같은 주문의 이벤트는 {@code orderNo} 라우팅 키로 각 토픽의 같은 파티션에서
 * 순서 보존된다. 토픽 사이 순서는 보장되지 않지만 서비스가 이미 방어한다(릴리스 선착 시
 * warn 후 재전달 대기).
 *
 * <p><b>실패 처리</b>: 여기서 던진 예외는 {@link KafkaConsumerConfig}의 에러 핸들러가 받아
 * 재시도 후 DLT로 보낸다(파싱 실패 포함). 실패 건이 파티션을 막지 않게 하는 선택의 근거는
 * ADR-016 참고.
 */
@Component
@RequiredArgsConstructor
class SettlementEventListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventListener.class);

    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = PayTopics.PAYMENT_CONFIRMED)
    void onConfirmed(ConsumerRecord<String, String> rec) {
        PaymentConfirmedEvent event = read(rec, PaymentConfirmedEvent.class);
        settlementService.registerConfirmedPayment(event);
        log.info("정산 적재 orderNo={} paymentId={} partition={} offset={}",
                event.orderNo(), event.paymentId(), rec.partition(), rec.offset());
    }

    @KafkaListener(topics = PayTopics.ESCROW_RELEASED)
    void onEscrowReleased(ConsumerRecord<String, String> rec) {
        EscrowReleasedEvent event = read(rec, EscrowReleasedEvent.class);
        // 릴리스 시각의 UTC 날짜가 집계 기준일 — 정산은 승인일이 아니라 구매확정일 기준.
        LocalDate releaseDate = LocalDate.ofInstant(event.releasedAt(), ZoneOffset.UTC);
        settlementService.confirmSettlement(event.orderNo(), releaseDate);
    }

    @KafkaListener(topics = PayTopics.PAYMENT_CANCELED)
    void onCanceled(ConsumerRecord<String, String> rec) {
        settlementService.reflectCancellation(read(rec, PaymentCanceledEvent.class));
    }

    /**
     * JSON 페이로드를 계약 record로 역직렬화한다. 실패는 그대로 던져 에러 핸들러의
     * 재시도·DLT 경로에 태운다 — poison message가 파티션을 영구히 막지 않게 한다.
     */
    private <T> T read(ConsumerRecord<String, String> rec, Class<T> type) {
        try {
            return objectMapper.readValue(rec.value(), type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "이벤트 역직렬화 실패 topic=%s partition=%d offset=%d"
                            .formatted(rec.topic(), rec.partition(), rec.offset()), e);
        }
    }
}
