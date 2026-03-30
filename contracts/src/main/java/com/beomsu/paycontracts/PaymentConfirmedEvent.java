package com.beomsu.paycontracts;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;

/**
 * 결제 승인 완료 이벤트. 인프로세스에선 ledger(분개)·order(주문 확정)·settlement가 구독하고,
 * 프로세스 밖 소비자(consumer-app, 분리될 settlement-service)를 위해 Kafka로도 외부화된다.
 * Zero-Payload 지향 — 식별자와 최소 정보만 담아 순서 역전·스키마 결합을 피한다.
 *
 * <p>발행자와 프로세스 밖 소비자가 공유하는 <b>계약(published language)</b>이라 pay 본체가 아닌
 * pay-contracts 아티팩트에 둔다. 필드 변경은 소비자 전부와의 호환을 검토한 뒤에만 한다.
 *
 * <p>{@code @Externalized}는 {@code 토픽명::라우팅키} 형식이며, 라우팅 키를 {@code orderNo}
 * (파티션 키)로 잡아 <b>같은 주문의 이벤트가 같은 파티션에 들어가 순서가 보존</b>되게 한다.
 * 인프로세스 소비(Outbox + {@code @ApplicationModuleListener})는 그대로 유지되고, 외부화는
 * 브로커가 있을 때만 켠다(application.yml의
 * {@code spring.modulith.events.externalization.enabled} 게이트).
 */
@Externalized(PayTopics.PAYMENT_CONFIRMED + "::#{orderNo}")
public record PaymentConfirmedEvent(String orderNo, Long paymentId, long amount, Instant approvedAt) {
}
