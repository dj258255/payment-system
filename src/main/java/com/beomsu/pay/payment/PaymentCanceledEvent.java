package com.beomsu.pay.payment;

/** 결제 취소(전액/부분) 이벤트. ledger가 역분개를 위해 구독한다. */
public record PaymentCanceledEvent(String orderNo, Long paymentId, long cancelAmount, boolean fullyCanceled) {
}
