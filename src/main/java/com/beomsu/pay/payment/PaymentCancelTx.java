package com.beomsu.pay.payment;

import com.beomsu.pay.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 취소 사가의 <b>트랜잭션 경계</b> — 대상 확정(Phase 1)과 결과 반영(Phase 3)을 각각 짧은 트랜잭션으로
 * 담는다. 그 사이의 PG 취소 호출(Phase 2)은 {@link PaymentService}가 트랜잭션 밖에서 한다.
 *
 * <p><b>왜 나눴나</b> — 외부 API를 트랜잭션 안에서 부르면 두 가지가 깨진다. 첫째, 느린 PG가 응답할
 * 때까지 DB 커넥션을 붙잡아 폭주 시 풀이 마른다. 둘째, PG 취소가 성공한 뒤 커밋이 실패하면 롤백이
 * 오히려 불일치를 만든다(PG는 취소됐는데 우리 장부는 안 취소됨). 승인 경로가 {@code CheckoutTx}로
 * 같은 문제를 이미 푼 방식을 취소에도 그대로 적용한다.
 *
 * <p><b>왜 PG를 먼저 부르고 나중에 기록하나</b> — 반대 순서(기록 먼저)면 PG 취소가 실패했을 때
 * 우리만 취소로 남는다. PG 취소는 {@code paymentKey:cancelAmount} 멱등키로 보호되므로, Phase 3이
 * 실패해 재시도해도 두 번 취소되지 않고 "이미 취소됨" 응답이 성공으로 흡수된다. 그래서
 * <b>바깥을 먼저 확정하고 우리 기록을 맞추는</b> 순서가 안전하다.
 *
 * <p>별도 빈으로 둔 이유: 비트랜잭션 오케스트레이터가 이 메서드들을 프록시로 호출해야
 * {@code @Transactional}이 실제 경계가 된다(자기호출이면 우회된다).
 */
@Component
@RequiredArgsConstructor
class PaymentCancelTx {

    static final List<PaymentStatus> CANCELABLE =
            List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher events;

    /**
     * 취소 대상 — Phase 2가 PG를 부르고 Phase 3이 다시 찾는 데 쓰는 키.
     *
     * <p>결제 식별자가 아니라 {@code paymentKey}로 잡는다. PG 취소가 이 키로 이뤄지므로, 재시도가
     * 같은 키로 같은 결제를 다시 집는다.
     */
    record CancelTarget(String paymentKey) {}

    /**
     * Phase 1 — 취소 대상 확정(짧은 tx, 읽기 전용). 취소 가능 여부까지 여기서 걸러
     * <b>불가능한 취소를 PG에 보내지 않는다</b>. 상태는 바꾸지 않는다.
     */
    @Transactional(readOnly = true)
    public CancelTarget resolveByOrderNo(String orderNo, Money cancelAmount) {
        Payment payment = paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, CANCELABLE)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "취소할 결제를 찾을 수 없습니다: " + orderNo));
        payment.validateCancelable(cancelAmount);
        return new CancelTarget(payment.getPaymentKey());
    }

    /** Phase 1 — 결제 식별자로 취소 대상을 확정한다. */
    @Transactional(readOnly = true)
    public CancelTarget resolveById(Long paymentId, Money cancelAmount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        payment.validateCancelable(cancelAmount);
        return new CancelTarget(payment.getPaymentKey());
    }

    /**
     * Phase 3 — PG 취소 성공을 결제에 반영(짧은 tx)하고 취소 이벤트를 발행한다.
     *
     * <p>이미 취소가 반영된 결제면 아무 것도 하지 않는다(멱등). Phase 2와 3 사이에서 앱이 죽어
     * 재시도로 다시 들어와도 이중 반영되지 않는다.
     */
    @Transactional
    public void apply(String paymentKey, Money cancelAmount, String reason) {
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentKey));
        if (!CANCELABLE.contains(payment.getStatus())) {
            return; // 이미 전액 취소 등으로 확정됨 — 재시도가 멱등하게 끝난다
        }
        payment.cancel(cancelAmount, TriggeredBy.USER, reason);

        // 취소 전이를 이벤트 발행 전에 flush로 확정한다. (managed 엔티티라 커밋 시 dirty-check로도
        // flush되지만, 발행 순서상 상태를 먼저 못박아 둔다.)
        paymentRepository.saveAndFlush(payment);

        boolean fullyCanceled = payment.getStatus() == PaymentStatus.CANCELED;
        // 취소 후 잔액(절대값)을 실어 정산이 멱등하게 반영하게 한다(델타 차감이 아니라 절대 잔액 세팅).
        events.publishEvent(new PaymentCanceledEvent(
                payment.getOrderNo(), payment.getId(), cancelAmount.amount(),
                payment.getBalanceAmount(), fullyCanceled));
    }
}
