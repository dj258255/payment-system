package com.beomsu.pay.fraud;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import com.beomsu.pay.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 비동기 사후 탐지 리스너 — 결제 완료 이벤트를 받아 판정 엔진으로 다시 평가하고, REVIEW/BLOCK이면
 * 심사 큐에 적재한다.
 *
 * <p>결제 크리티컬 경로({@code CheckoutService.confirm}/{@code PaymentService.confirm})는 절대
 * 건드리지 않는다. 사후 탐지는 오직 {@code PaymentConfirmedEvent} 구독으로만 붙어, 승인이 끝난 뒤
 * (Outbox at-least-once) 비동기로 돈다 — 결제 지연·실패에 영향을 주지 않는다. 동기 인라인 판정과
 * <b>같은 판정 엔진</b>({@link FraudService#evaluate})을 재사용해 룰 일관성을 지킨다.
 *
 * <p><b>사후 신호의 한계</b>: 요청 시점의 신호(ip/deviceId/userId)는 이벤트에 없다(Zero-Payload).
 * 그래서 사후 탐지는 cardKey({@code paymentKey})와 amount만으로 평가한다 — 활성 룰(블랙리스트·
 * velocity·금액 이상치)이 모두 cardKey·amount 기준이라 정상 동작하며, ip/device는 0/null로 채운다.
 *
 * <p>BLOCK은 사후엔 이미 결제가 완료돼 막을 수 없지만, 긴급 심사 대상으로 큐에 적재한다.
 * ALLOW/CHALLENGE는 큐에 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
class FraudPostHocListener {

    private final PaymentService paymentService;
    private final FraudService fraudService;
    private final FraudReviewRepository reviewRepository;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent e) {
        // 이벤트는 카드 키를 싣지 않으므로 paymentId로 되읽는다. 없으면 조용히 skip.
        String cardKey = paymentService.paymentKeyOf(e.paymentId()).orElse(null);
        if (cardKey == null) {
            return;
        }

        // 사후 재평가: ip/deviceId/userId는 요청 시점 신호라 사후엔 없다 → 0/null.
        // 활성 룰이 cardKey·amount 기준이라 이 입력으로도 정상 동작한다.
        FraudResult result = fraudService.evaluate(
                new FraudCheckRequest(0L, cardKey, null, null, e.amount()));

        // REVIEW/BLOCK만 심사 큐에 적재한다(ALLOW/CHALLENGE는 제외).
        if (result.decision() == FdsDecision.REVIEW || result.decision() == FdsDecision.BLOCK) {
            reviewRepository.save(
                    FraudReview.flagged(e.orderNo(), e.paymentId(), cardKey, e.amount(), result));
        }
    }
}
