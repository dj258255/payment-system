package com.beomsu.pay.order;

import com.beomsu.pay.payment.ApprovalOutcome;
import com.beomsu.pay.payment.ConfirmResult;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.payment.PaymentStatus;
import com.beomsu.pay.point.PointService;
import com.beomsu.pay.shared.Money;
import com.beomsu.pay.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 체크아웃 사가(ADR-007)의 <b>트랜잭션 경계</b> — 예약(Phase 1)과 확정(Phase 3)을 각각 짧은 트랜잭션으로
 * 담는다. 그 사이의 PG 승인(Phase 2)은 {@link CheckoutService}가 트랜잭션 밖에서 호출한다.
 *
 * <p>별도 빈으로 둔 이유: {@code CheckoutService.confirm}(오케스트레이터, 비트랜잭션)이 이 메서드들을
 * 프록시로 호출해야 각 단계의 {@code @Transactional}이 실제 트랜잭션 경계가 된다(자기호출이면 우회됨).
 */
@Component
@RequiredArgsConstructor
class CheckoutTx {

    /** 적립률(정책) — 실결제액(카드+월렛)의 %. 취소 시 적립 회수도 이 율을 쓴다. 쿠폰/등급 차등은 이후 확장. */
    static final long EARN_RATE_PERCENT = 1;

    private final PaymentService paymentService;
    private final OrderRepository orderRepository;
    private final StockDeductionService stockDeductionService;
    private final PointService pointService;
    private final WalletService walletService;
    private final CompensationService compensationService;

    /** 예약 결과 — 확정 단계로 넘길 최소 정보. cardAmount==0(전액 포인트)이면 paymentId는 null. */
    record Reservation(Long paymentId) {}

    /**
     * Phase 1 — 예약(짧은 tx). 검증 → 주문 상태 전이(이중지불 잠금) → 포인트 선점 → 결제 IN_PROGRESS 적재
     * → 월렛 차감. PG 콜 <b>전</b>에 커밋되어 커넥션을 반납한다. 크래시 시에도 이 상태(주문 IN_PROGRESS +
     * 결제 IN_PROGRESS + 포인트/월렛 예약)가 남아 복구 배치가 완결/롤백할 수 있다.
     *
     * <p><b>결제수단별 롤백 계약</b>: 포인트({@link PointService})는 이 tx에 합류하므로(클래스 @Transactional)
     * 예약 실패 시 자동 롤백된다. 월렛({@link WalletService#use})은 자체 짧은 tx로 <b>커밋되는</b> 부수효과라
     * 자동 롤백되지 않는다 — 그래서 월렛 차감을 이 메서드의 <b>맨 마지막</b>(order 저장 후)에 두어, 이후 in-tx
     * 실패로 커밋된 차감이 고아가 되는 창을 없앤다. 월렛 잔액이 부족하면 여기서 예외가 나 이 tx 전체가
     * 롤백되고(월렛은 아직 안 건드림), 보상은 {@link #settle}의 거절/재고부족 분기가 orderNo 멱등 환불로 한다.
     */
    @Transactional
    public Reservation reserve(String orderNo, String paymentKey, Money cardAmount,
                               long pointAmount, long walletAmount, long authenticatedUserId) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));
        order.verifyOwner(authenticatedUserId); // IDOR 방지 — 무엇보다 먼저

        long requestedTotal;
        try {
            requestedTotal = Math.addExact(Math.addExact(cardAmount.amount(), pointAmount), walletAmount);
        } catch (ArithmeticException e) {
            throw new OrderException("AMOUNT_OVERFLOW", "결제 금액이 허용 범위를 초과했습니다.");
        }
        order.verifyAmount(Money.of(requestedTotal));

        order.startPayment(); // PENDING_PAYMENT → PAYMENT_IN_PROGRESS (조건부 전이, 이중지불 차단)

        if (pointAmount > 0) {
            pointService.use(order.getUserId(), pointAmount, orderNo); // 롤백 확실한 내부 자원 선점(in-tx)
        }

        Long paymentId = null;
        if (cardAmount.amount() > 0) {
            paymentId = paymentService.beginApproval(orderNo, paymentKey, cardAmount);
        }
        orderRepository.saveAndFlush(order);

        // 월렛 차감은 맨 마지막 — 커밋되는 부수효과라 이후 단계 실패로 고아가 되지 않게 한다. orderNo로 멱등.
        if (walletAmount > 0) {
            walletService.use(order.getUserId(), walletAmount, orderNo);
        }
        return new Reservation(paymentId);
    }

    /**
     * Phase 3 — 확정/보상(짧은 tx). PG 결과({@code outcome})를 결제에 반영하고 주문을 확정하거나 보상한다.
     * PG 결과가 없으면(전액 포인트) 승인 성공으로 간주한다. 재고 차감은 승인 성공 시점에 한다(ADR-003).
     *
     * <p>이 메서드는 <b>재진입 가능</b>하다 — 멈춘 사가 복구가 PG 조회 결과로 이 메서드를 재실행해도
     * {@code applyResult}가 멱등(IN_PROGRESS일 때만 전이)이라 안전하다.
     */
    @Transactional
    public CheckoutResult settle(String orderNo, Long paymentId, Money cardAmount,
                                 long pointAmount, long walletAmount, ApprovalOutcome outcome) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        ConfirmResult result;
        boolean cardApproved;
        if (paymentId != null) {
            result = paymentService.applyResult(paymentId, outcome); // 결제 확정(멱등), 이 tx에 합류
            cardApproved = result.isApproved();
        } else {
            result = null; // 전액 포인트 — PG 결과 없음
            cardApproved = true;
        }

        if (cardApproved) {
            // 승인 성공 시점 재고 차감(ADR-003). tryDeduct(예외 없는 boolean) — 부족해도 tx는 깨끗이 커밋하고 보상.
            List<OrderItem> deducted = new ArrayList<>();
            boolean allDeducted = true;
            for (OrderItem item : order.getItems()) {
                if (stockDeductionService.tryDeduct(item.getProductId(), item.getQuantity())) {
                    deducted.add(item);
                } else {
                    allDeducted = false;
                    break;
                }
            }
            if (allDeducted) {
                order.markPaid();
                // 실결제액(카드+월렛, 포인트 사용분 제외) 기준 적립 — 포인트로 포인트를 버는 이중적립 방지.
                // earn은 orderNo 멱등이라 복구가 이 성공분기를 재실행해도 이중적립되지 않는다.
                long paidByMoney = cardAmount.amount() + walletAmount;
                pointService.earn(order.getUserId(), paidByMoney * EARN_RATE_PERCENT / 100, orderNo);
            } else {
                // 승인 후 재고 부족 → 자동 보상(망취소). 예외를 던지지 않아 tx는 깨끗이 커밋된다.
                for (OrderItem d : deducted) {
                    stockDeductionService.restore(d.getProductId(), d.getQuantity());
                }
                if (pointAmount > 0) {
                    pointService.restore(order.getUserId(), pointAmount, orderNo);
                }
                if (walletAmount > 0) {
                    walletService.restore(order.getUserId(), walletAmount, orderNo); // 예약 해제(멱등)
                }
                if (cardAmount.amount() > 0) {
                    compensationService.enqueueNetworkCancel(orderNo, cardAmount.amount(),
                            "재고 부족: 카드 승인 후 자동 망취소");
                }
                order.markFailed();
                orderRepository.saveAndFlush(order);
                return new CheckoutResult(orderNo, order.getStatus(),
                        (result != null ? result.status() : PaymentStatus.DONE),
                        "재고가 부족해 결제를 취소 처리했습니다. 승인된 카드는 자동으로 취소됩니다.");
            }
        } else if (result.isUnknown()) {
            // 미확정: 포인트·월렛 예약을 <b>그대로 유지</b>한다(결제가 실제로 승인됐을 수 있으므로). 주문은
            // PAYMENT_IN_PROGRESS로 두고, 복구 배치가 PG 조회로 확정한다 — DONE이면 예약분이 그대로 소비된 채
            // PAID가 되고, ABORTED면 아래 거절 분기가 그때 복원한다. 여기서 미리 복원하면, 이후 완결(SUCCESS
            // 분기)이 예약분을 재소비하지 않아 가맹점이 그만큼 덜 걷는다(자금 손실).
        } else {
            // 명시적 거절: 선점 포인트·월렛 예약 해제(RESTORE), 재시도 위해 주문 PENDING_PAYMENT로 복귀.
            // 해제는 멱등이고, USE 멱등이 활성예약(USE−RESTORE−REFUND) 기준이라 재시도 시 다시 차감된다
            // (거절→재시도 이중무료 방지). point.restore와 wallet.restore가 대칭이다.
            if (pointAmount > 0) {
                pointService.restore(order.getUserId(), pointAmount, orderNo);
            }
            if (walletAmount > 0) {
                walletService.restore(order.getUserId(), walletAmount, orderNo);
            }
            order.revertToPending();
        }

        orderRepository.saveAndFlush(order);
        PaymentStatus paymentStatus = (result != null) ? result.status() : PaymentStatus.DONE;
        String message = (result != null) ? result.message() : "포인트 전액 결제 완료";
        return new CheckoutResult(orderNo, order.getStatus(), paymentStatus, message);
    }
}
