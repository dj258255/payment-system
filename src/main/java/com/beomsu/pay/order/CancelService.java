package com.beomsu.pay.order;

import com.beomsu.pay.order.RefundAllocator.RefundAllocation;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.point.PointService;
import com.beomsu.pay.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 취소 오케스트레이션 애플리케이션 서비스 — order 모듈의 취소 진입점.
 *
 * <p>환불은 <b>포인트 우선</b>으로 배분한다({@link RefundAllocator}). 포인트 몫은 point 모듈에,
 * 카드 몫은 payment 모듈에 위임한다. 카드 취소가 발행하는 {@code PaymentCanceledEvent}를 receipt·
 * ledger가 구독해 현금영수증 연쇄취소·원장 역분개가 자동으로 이어진다.
 *
 * <p>전액 취소일 때만 재고를 복원하고 주문을 CANCELED로 전이한다. 부분취소는 금액 단위라 수량 단위
 * 재고에 매핑되지 않으므로 재고를 복원하지 않고 주문 상태도 PAID로 유지한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CancelService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final PointService pointService;
    private final StockDeductionService stockDeductionService;

    /**
     * 주문 취소(전액/부분). 처리 순서:
     * <ol>
     *   <li>취소 금액 방어(0 이하 거부)</li>
     *   <li>주문 로드</li>
     *   <li><b>소유권 검증</b> — 그 무엇보다 먼저(IDOR 방지)</li>
     *   <li>상태 검증(PAID만 취소 가능)</li>
     *   <li>잔여 결제분 조회(포인트/카드) → 포인트 우선 배분</li>
     *   <li>포인트 환불 + 카드 취소 위임</li>
     *   <li>전액 취소면 재고 복원 + 주문 CANCELED, 부분취소면 재고 유지 + PAID 유지</li>
     * </ol>
     */
    public CancelResult cancel(String orderNo, long cancelAmount, String reason, long authenticatedUserId) {
        // 1. 취소 금액 방어 — 0 이하 거부.
        if (cancelAmount <= 0) {
            throw new OrderException("INVALID_REQUEST", "취소 금액은 0보다 커야 합니다: " + cancelAmount);
        }

        // 2. 주문 로드
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        // 3. 소유권 검증 — 남의 주문을 취소·환불받는 것(IDOR)을 막는다. 그 무엇보다 먼저.
        order.verifyOwner(authenticatedUserId);

        // 4. 상태 검증 — 결제 완료(PAID) 주문만 취소할 수 있다.
        if (order.getStatus() != OrderStatus.PAID) {
            throw new OrderException("INVALID_STATE_TRANSITION", "결제 완료 주문만 취소할 수 있습니다.");
        }

        // 5. 잔여 결제분 조회 — 이미 환불된 몫을 제외한 포인트·카드 잔액.
        long paidByPoint = pointService.refundableAmount(orderNo);
        long paidByCard = paymentService.cardBalance(orderNo);

        // 6. 포인트 우선 배분. 잔여 초과면 RefundAllocator가 IllegalArgumentException을 던진다.
        RefundAllocation alloc;
        try {
            alloc = RefundAllocator.allocate(cancelAmount, paidByPoint, paidByCard);
        } catch (IllegalArgumentException e) {
            throw new OrderException("CANCEL_AMOUNT_EXCEEDED", e.getMessage());
        }

        // 7. 포인트 환불(내부 자원 — 즉시·확실).
        if (alloc.fromPoint() > 0) {
            pointService.refund(order.getUserId(), alloc.fromPoint(), orderNo);
        }

        // 8. 카드 취소 위임 — PaymentCanceledEvent로 현금영수증 연쇄취소·원장 역분개가 이어진다.
        if (alloc.fromCard() > 0) {
            paymentService.cancelByOrderNo(orderNo, Money.of(alloc.fromCard()), reason);
        }

        // 9. 전액 취소 판정.
        boolean fully = (cancelAmount == paidByPoint + paidByCard);
        if (fully) {
            // 전액 취소: 차감했던 재고를 복원하고 주문을 CANCELED로 전이한다.
            for (OrderItem item : order.getItems()) {
                stockDeductionService.restore(item.getProductId(), item.getQuantity());
            }
            order.cancel();
            // 상태 전이(CANCELED)를 명시적으로 영속한다. OSIV off 환경에서 detached 엔티티는
            // dirty-checking 자동 flush가 일어나지 않으므로 저장을 명시한다(flush 강제).
            orderRepository.saveAndFlush(order);
        }
        // 부분취소는 금액 단위라 수량 단위 재고에 매핑되지 않아 복원하지 않는다. 주문은 PAID 유지.

        return new CancelResult(orderNo, order.getStatus(), alloc.fromPoint(), alloc.fromCard(), fully);
    }
}
