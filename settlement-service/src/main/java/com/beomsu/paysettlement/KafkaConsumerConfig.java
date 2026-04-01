package com.beomsu.paysettlement;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 컨슈머 실패 처리 — <b>재시도 후 DLT</b>(A안).
 *
 * <p>레코드 처리 실패 시 1초 간격 3회 재시도하고, 소진되면
 * {@link DeadLetterPublishingRecoverer}가 {@code <원본토픽>-dlt}로 보낸 뒤 오프셋을 커밋한다.
 * 파티션은 계속 흐르므로 한 건의 실패(예: poison message)가 뒤 주문들의 정산을 막지 않는다.
 *
 * <p>대안(B안: 커밋 보류 무한 재시도)은 유실 0이지만 head-of-line blocking으로 파티션 전체가
 * 정지한다. 선택 근거와 실측은 ADR-009 참고 — DLT로 빠진 건은 자동 처리에서 이탈하지만,
 * 대사(reconciliation)가 결제·정산 원장 불일치를 최종 검출하는 방어선이 이미 있다.
 */
@Configuration
class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.setRetryListeners((ConsumerRecord<?, ?> rec, Exception ex, int attempt) ->
                log.warn("정산 이벤트 처리 실패 재시도 {}/3 topic={} offset={} cause={}",
                        attempt, rec.topic(), rec.offset(), ex.getMessage()));
        return handler;
    }
}
