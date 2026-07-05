package com.beomsu.pay.reconciliation;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 승인 이벤트를 내부 기록으로 적재하는 리스너.
 *
 * <p>{@code @ApplicationModuleListener}로 결제 완료 이벤트를 받아, 대사의 기준이 되는 내부
 * 기대치({@link InternalRecord})를 쌓는다. 서비스가 (orderNo 유니크로) 멱등하게 적재한다.
 */
@Component
@RequiredArgsConstructor
class PaymentReconciliationListener {

    private final ReconciliationService reconciliationService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        reconciliationService.recordInternal(event);
    }
}
