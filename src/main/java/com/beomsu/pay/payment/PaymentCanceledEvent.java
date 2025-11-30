package com.beomsu.pay.payment;

import org.springframework.modulith.events.Externalized;

/**
 * 결제 취소(전액/부분) 이벤트. ledger가 역분개를 위해 구독한다.
 *
 * <p>승인 이벤트와 마찬가지로 {@code @Externalized}로 Kafka에 외부화하며, 라우팅 키를
 * {@code orderNo}로 잡아 같은 주문의 승인/취소 이벤트가 같은 파티션에서 순서대로 흐르게 한다.
 */
@Externalized("payment.canceled::#{orderNo}")
public record PaymentCanceledEvent(String orderNo, Long paymentId, long cancelAmount, boolean fullyCanceled) {
}
