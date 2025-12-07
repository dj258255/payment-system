package com.beomsu.pay.payment;

import com.beomsu.pay.payment.pg.PgApproveCommand;
import com.beomsu.pay.payment.pg.PgApproveResult;
import com.beomsu.pay.payment.pg.PgClient;
import com.beomsu.pay.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 결제 애플리케이션 서비스 — payment 모듈의 공개 진입점.
 *
 * <p>승인/취소를 오케스트레이션한다. 금액 위변조 검증은 주문 금액의 소유자인 order 모듈이
 * 담당하므로, 이 서비스는 이미 검증된 금액을 받는다고 가정한다.
 * PG 호출 결과는 3-상태(SUCCESS/FAILED/TIMEOUT)로 나뉘며, TIMEOUT은 UNKNOWN으로 보존한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;

    /**
     * 결제 승인. order 모듈이 금액 검증·주문 상태 전이를 마친 뒤 호출한다.
     */
    @Transactional
    public ConfirmResult confirm(String orderNo, String paymentKey, Money amount) {
        Payment payment = Payment.initiate(orderNo, amount);
        payment.startApproval(paymentKey);
        paymentRepository.save(payment);

        PgApproveResult result = pgClient.approve(
                new PgApproveCommand(paymentKey, orderNo, amount.amount()));

        // 관측성: 승인 결과를 결과별로 계측한다. Grafana의 "결제 성공률" 패널의 소스.
        meterRegistry.counter("payment.confirm", "outcome", result.outcome().name().toLowerCase())
                .increment();

        ConfirmResult confirmResult = switch (result.outcome()) {
            case SUCCESS -> {
                payment.approve(result.method());
                events.publishEvent(new PaymentConfirmedEvent(
                        orderNo, payment.getId(), amount.amount(), payment.getApprovedAt()));
                yield new ConfirmResult(payment.getId(), payment.getStatus(),
                        result.method(), "승인 완료");
            }
            case FAILED -> {
                payment.abort(result.failReason());
                yield new ConfirmResult(payment.getId(), payment.getStatus(),
                        null, result.failReason());
            }
            case TIMEOUT -> {
                // 미확정: 실패로 단정하지 않는다. 복구 배치/망취소(Phase 2)가 확정한다.
                payment.markUnknown(result.failReason());
                yield new ConfirmResult(payment.getId(), payment.getStatus(),
                        null, "결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.");
            }
        };
        // 상태 전이(approve/abort/markUnknown)를 명시적으로 영속한다. 이 payment는 위에서 persist된
        // '관리(managed)' 엔티티라 save(merge)는 no-op이 되어, dirty-checking 자동 flush가 일어나지 않는
        // 이 트랜잭션에서는 승인 결과가 확정되지 않는다. 그래서 saveAndFlush로 UPDATE를 강제한다.
        // (finder로 로드한 detached 엔티티는 save(merge)만으로 확정되지만, 여기선 그 경우가 아니다.)
        paymentRepository.saveAndFlush(payment);
        return confirmResult;
    }

    private static final List<PaymentStatus> CANCELABLE =
            List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    /**
     * 결제 취소(전액/부분). PG 취소 호출이 실패하면 트랜잭션이 롤백된다(Phase 2에서 보상으로 강화).
     */
    @Transactional
    public void cancel(Long paymentId, Money cancelAmount, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        cancelPayment(payment, cancelAmount, reason);
    }

    /**
     * 주문번호로 성공한 결제를 찾아 취소한다. order 모듈의 취소 오케스트레이션이 호출한다.
     * 전액 포인트 결제(카드 결제 없음)면 취소할 결제가 없으므로 호출되지 않는다.
     */
    @Transactional
    public void cancelByOrderNo(String orderNo, Money cancelAmount, String reason) {
        Payment payment = paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, CANCELABLE)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "취소할 결제를 찾을 수 없습니다: " + orderNo));
        cancelPayment(payment, cancelAmount, reason);
    }

    /** 주문의 카드 취소 가능 잔액. 성공한 결제가 없으면(전액 포인트 등) 0. */
    @Transactional(readOnly = true)
    public long cardBalance(String orderNo) {
        return paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, CANCELABLE)
                .map(Payment::getBalanceAmount)
                .orElse(0L);
    }

    private void cancelPayment(Payment payment, Money cancelAmount, String reason) {
        payment.cancel(cancelAmount, TriggeredBy.USER, reason);
        pgClient.cancel(payment.getPaymentKey(), cancelAmount.amount(), reason);

        // 상태 전이(취소)를 명시적으로 영속한다. OSIV off 환경에서 detached 엔티티는 dirty-checking
        // 자동 flush가 일어나지 않으므로, 이벤트 발행 전에 취소 상태를 DB에 확정한다(flush 강제).
        paymentRepository.saveAndFlush(payment);

        boolean fullyCanceled = payment.getStatus() == PaymentStatus.CANCELED;
        events.publishEvent(new PaymentCanceledEvent(
                payment.getOrderNo(), payment.getId(), cancelAmount.amount(), fullyCanceled));
    }
}
