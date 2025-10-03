package com.beomsu.pay.payment;

import java.time.Instant;

/**
 * 결제 승인 완료 이벤트. ledger(분개)·order(주문 확정)·settlement가 구독한다.
 * Zero-Payload 지향 — 식별자와 최소 정보만 담아 순서 역전·스키마 결합을 피한다.
 */
public record PaymentConfirmedEvent(String orderNo, Long paymentId, long amount, Instant approvedAt) {
}
