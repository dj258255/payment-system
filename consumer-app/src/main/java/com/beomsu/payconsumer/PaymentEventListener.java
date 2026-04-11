package com.beomsu.payconsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 결제 이벤트 Kafka 리스너 (정산 알림 데모).
 *
 * <p><b>at-least-once</b>: 메인 앱의 Outbox(event_publication)가 발행을 보장하는 대신
 * 재발행·재시도로 <b>같은 이벤트가 중복 수신될 수 있다</b>. 실소비자는 orderNo/paymentId 기반
 * 멱등 처리(processed_events 같은 소비 이력)가 필수다 — 여기서는 로그 데모라 카운트만 한다.
 *
 * <p>이벤트는 Zero-Payload 지향(식별자 + 최소 정보만)이다. 상세가 필요한 실소비자는
 * 페이로드를 신뢰 원천으로 삼지 말고 orderNo로 조회 API를 되읽어 최신 상태를 확정하는
 * 패턴을 쓴다(순서 역전·스키마 결합 회피).
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    // 웹 없는 경량 워커라 Jackson 자동구성(ObjectMapper 빈)이 없다(Jackson2ObjectMapperBuilder가
    // spring-web 소속) → 직접 생성한다. readTree만 쓰므로 커스터마이징 불필요.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong confirmedCount = new AtomicLong();
    private final AtomicLong canceledCount = new AtomicLong();

    @KafkaListener(topics = "payment.confirmed")
    public void onConfirmed(ConsumerRecord<String, String> rec) {
        JsonNode json = parse(rec);
        if (json == null) {
            return;
        }
        long total = confirmedCount.incrementAndGet();
        log.info("[정산알림] 결제 완료 수신 orderNo={} amount={} partition={} offset={} (누적 {}건)",
                json.path("orderNo").asText(), json.path("amount").asLong(),
                rec.partition(), rec.offset(), total);
    }

    @KafkaListener(topics = "payment.canceled")
    public void onCanceled(ConsumerRecord<String, String> rec) {
        JsonNode json = parse(rec);
        if (json == null) {
            return;
        }
        long total = canceledCount.incrementAndGet();
        log.info("[정산알림] 결제 취소 수신 orderNo={} cancelAmount={} fullyCanceled={} partition={} offset={} (누적 {}건)",
                json.path("orderNo").asText(), json.path("cancelAmount").asLong(),
                json.path("fullyCanceled").asBoolean(),
                rec.partition(), rec.offset(), total);
    }

    /**
     * 파싱 실패는 warn 로그 후 skip한다 — 포이즌 메시지 하나가 파티션 소비 전체를
     * 멈추게 두지 않는다(예외를 던지면 컨테이너가 같은 오프셋을 무한 재시도한다).
     */
    private JsonNode parse(ConsumerRecord<String, String> rec) {
        try {
            return objectMapper.readTree(rec.value());
        } catch (Exception e) {
            log.warn("[정산알림] 파싱 실패 — skip. topic={} partition={} offset={} value={}",
                    rec.topic(), rec.partition(), rec.offset(), rec.value(), e);
            return null;
        }
    }

    long confirmedCount() {
        return confirmedCount.get();
    }

    long canceledCount() {
        return canceledCount.get();
    }
}
