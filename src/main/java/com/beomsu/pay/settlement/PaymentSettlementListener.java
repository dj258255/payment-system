package com.beomsu.pay.settlement;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 승인 이벤트를 정산 대상으로 적재하는 리스너.
 *
 * <p>{@code @ApplicationModuleListener}로 결제 완료 이벤트를 받아, 하루치 배치가 집계할
 * 정산 항목({@link SettlementItem})으로 쌓는다. Modulith 이벤트 레지스트리(=Outbox)가 유실을 막고,
 * 서비스가 (paymentId 유니크로) 멱등하게 적재한다.
 *
 * <p>취소(PaymentCanceledEvent) 구독은 Phase 4 범위 밖이라 생략한다 — 실무에선 취소분을
 * 음수 정산 항목(역집계)으로 반영해 지급금을 차감한다.
 */
@Component
@RequiredArgsConstructor
class PaymentSettlementListener {

    private final SettlementService settlementService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        settlementService.registerConfirmedPayment(event);
    }
}
