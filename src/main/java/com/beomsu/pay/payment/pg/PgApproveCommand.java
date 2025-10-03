package com.beomsu.pay.payment.pg;

/** PG 승인 요청. 멱등키는 Phase 2에서 추가된다. */
public record PgApproveCommand(String paymentKey, String orderNo, long amount) {
}
