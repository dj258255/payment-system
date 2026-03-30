package com.beomsu.paycontracts;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;

/**
 * 에스크로 릴리스(구매확정) 이벤트 — 보류금이 정산 가능해졌음을 알린다.
 *
 * <p>정산(settlement)이 구독해 항목을 CONFIRMED(정산 가능)로 전이한다 — 정산을 승인 시점이
 * 아니라 구매확정 시점에 정렬하는 핵심 신호다. Zero-Payload 지향 — 식별자와 최소 정보만 담는다.
 *
 * <p>발행자와 프로세스 밖 소비자가 공유하는 <b>계약(published language)</b>이라 pay 본체가 아닌
 * pay-contracts 아티팩트에 둔다. 필드 변경은 소비자 전부와의 호환을 검토한 뒤에만 한다.
 *
 * <p>{@code @Externalized}로 프로세스 밖 소비자를 위해 Kafka로도 외부화한다. 정산이 별도
 * 서비스로 분리되면 이 이벤트가 브로커를 타야 하므로, 결제 이벤트와 동일하게 라우팅 키를
 * {@code orderNo}(파티션 키)로 잡아 같은 주문의 결제·에스크로 이벤트 순서가 각 토픽의 파티션
 * 안에서 보존되게 한다. 토픽 사이의 순서는 보장되지 않으므로 소비자는 상태 가드로 방어한다.
 */
@Externalized(PayTopics.ESCROW_RELEASED + "::#{orderNo}")
public record EscrowReleasedEvent(String orderNo, long amount, Instant releasedAt) {
}
