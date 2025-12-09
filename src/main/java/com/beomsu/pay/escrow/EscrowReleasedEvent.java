package com.beomsu.pay.escrow;

import java.time.Instant;

/**
 * 에스크로 릴리스(구매확정) 이벤트 — 보류금이 정산 가능해졌음을 알린다.
 *
 * <p>향후 정산 파이프라인이 이 이벤트를 구독해 판매자 지급 대상으로 적재한다(구매확정 전 정산 방지의
 * 핵심). 지금은 발행만 하고 구독자는 두지 않는다. Zero-Payload 지향 — 식별자와 최소 정보만 담는다.
 */
public record EscrowReleasedEvent(String orderNo, long amount, Instant releasedAt) {
}
