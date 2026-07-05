package com.beomsu.pay.order;

import com.beomsu.pay.payment.ConfirmResult;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.payment.PaymentStatus;
import com.beomsu.pay.point.PointService;
import com.beomsu.pay.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 체크아웃(주문 생성 + 결제 승인 오케스트레이션) 애플리케이션 서비스 — order 모듈의 공개 진입점.
 *
 * <p>금액 위변조 검증은 total_amount의 소유자인 주문이 담당하고(이 모듈의 핵심), 실제 PG 승인은
 * payment 모듈({@link PaymentService})에 위임한다. 재고 차감은 ADR-003에 따라 <b>승인 성공 시점</b>에
 * 수행한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CheckoutService {

    private final PaymentService paymentService;
    private final OrderRepository orderRepository;
    private final StockDeductionService stockDeductionService;
    private final ProductRepository productRepository;
    private final PointService pointService;
    private final CompensationService compensationService;

    /**
     * 주문 생성 — totalAmount를 확정 저장한다(이후 금액 위변조 검증의 기준값). 재고는 차감하지 않는다.
     *
     * <p>가격은 클라이언트가 보낸 값이 아니라 서버의 {@link Product} 카탈로그에서 조회한다.
     * 이래야 금액 위변조 검증의 기준값 자체를 신뢰할 수 있다.
     */
    public CreateOrderResult createOrder(long userId, List<OrderLine> lines) {
        List<OrderItem> items = lines.stream()
                .map(l -> {
                    Product product = productRepository.findById(l.productId())
                            .orElseThrow(() -> OrderException.productNotFound(l.productId()));
                    return OrderItem.of(product.getProductId(), product.getName(),
                            product.getPrice(), l.quantity());
                })
                .toList();
        Order order = Order.create(userId, items);
        orderRepository.save(order);
        return new CreateOrderResult(order.getOrderNo(), order.getTotalAmount(), order.getExpiresAt());
    }

    /**
     * 복합결제 승인(포인트+카드). 서버 내부 처리 순서(10-API-스펙):
     * <ol>
     *   <li>주문 로드 (없으면 ORDER_NOT_FOUND)</li>
     *   <li>금액 위변조 검증 — 카드금액 + 포인트금액 == 주문 총액. 불일치 시 AMOUNT_MISMATCH (PG 호출 이전 차단)</li>
     *   <li>주문 상태 조건부 전이 PENDING_PAYMENT → PAYMENT_IN_PROGRESS (이중 지불 차단)</li>
     *   <li><b>포인트 선점</b> — 롤백이 확실한 내부 자원을 먼저 잡는다</li>
     *   <li>카드 승인 호출(payment 모듈 위임). 전액 포인트(cardAmount==0)면 카드 호출 생략</li>
     *   <li>결과 분기: 승인/전액포인트 → 재고 차감 + PAID / 미확정 → 유지 / 거절 → 복귀.
     *       <b>카드 실패·미확정 시 선점한 포인트를 복원(보상 트랜잭션)한다.</b></li>
     * </ol>
     *
     * <p>{@code pointAmount == 0}이면 순수 카드결제로, 기존 단일 카드결제와 완전히 동일하게 동작한다.
     * userId는 주문에서 얻는다(order가 소유자).
     *
     * @param cardAmount  카드로 결제할 금액
     * @param pointAmount 포인트로 결제할 금액(요청에 없으면 0)
     */
    public CheckoutResult confirm(String orderNo, String paymentKey, Money cardAmount,
                                  long pointAmount, long authenticatedUserId) {
        // 0. 음수 방어 — 음수 pointAmount로 검증 우회·오버플로를 시도할 수 없게 한다.
        if (pointAmount < 0 || cardAmount.amount() < 0) {
            throw new OrderException("INVALID_REQUEST", "결제 금액은 음수일 수 없습니다.");
        }

        // 1. 주문 로드
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        // 1.5. 소유권 검증 — 인증된 사용자가 이 주문의 주인인지 (IDOR 방지).
        //      검증·상태전이·포인트차감 그 무엇보다 먼저 한다.
        order.verifyOwner(authenticatedUserId);

        // 2. 금액 위변조 검증 — 카드+포인트 합이 주문 총액과 일치해야 한다.
        //    합산은 오버플로 시 조용히 뒤집히지 않도록 addExact를 쓴다.
        //    불일치 시 여기서 예외가 나므로 포인트 차감·PG 승인은 호출되지 않는다.
        long requestedTotal;
        try {
            requestedTotal = Math.addExact(cardAmount.amount(), pointAmount);
        } catch (ArithmeticException e) {
            throw new OrderException("AMOUNT_OVERFLOW", "결제 금액이 허용 범위를 초과했습니다.");
        }
        order.verifyAmount(Money.of(requestedTotal));

        // 3. 주문 상태 조건부 전이 (이중 지불 차단)
        order.startPayment();

        // 4. 포인트 선점 — 롤백이 확실한 내부 자원을 먼저 선점한다(카드 실패 시 복원으로 보상).
        if (pointAmount > 0) {
            pointService.use(order.getUserId(), pointAmount, orderNo);
        }

        // 5. 카드 승인 위임. 전액 포인트(cardAmount==0)면 카드 호출을 생략하고 승인 성공으로 간주한다.
        ConfirmResult result;
        boolean cardApproved;
        if (cardAmount.amount() > 0) {
            result = paymentService.confirm(orderNo, paymentKey, cardAmount);
            cardApproved = result.isApproved();
        } else {
            result = null; // 전액 포인트 결제 — 외부 PG 호출 없음
            cardApproved = true;
        }

        // 6. 결과 분기
        if (cardApproved) {
            // 재고 차감 (ADR-003: 승인 성공 시점 차감). 전략은 조건부 UPDATE(ADR-004: 부하테스트에서 최속).
            //
            // 카드 승인은 PG(외부)에서 이미 일어나 DB 롤백으로 되돌릴 수 없다. 그래서 재고 부족을 예외로
            // 던져 트랜잭션을 통째로 롤백하면 "돈은 나갔는데 주문은 사라진" 불일치가 남는다. 대신
            // tryDeduct(예외 없는 boolean)로 차감하고, 부족하면 트랜잭션을 깨끗이 커밋시킨 채 보상한다.
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
            } else {
                // 승인 후 재고 부족 → 자동 보상(망취소). 예외를 던지지 않아 트랜잭션은 깨끗이 커밋된다.
                for (OrderItem d : deducted) {
                    stockDeductionService.restore(d.getProductId(), d.getQuantity()); // 부분 차감분 원복
                }
                if (pointAmount > 0) {
                    pointService.restore(order.getUserId(), pointAmount, orderNo); // 선점 포인트 복원(내부·확실)
                }
                if (cardAmount.amount() > 0) {
                    // 외부·불확실한 PG 취소는 durable 보상 태스크로 적재해 스케줄러가 재시도로 망취소한다.
                    compensationService.enqueueNetworkCancel(orderNo, cardAmount.amount(),
                            "재고 부족: 카드 승인 후 자동 망취소");
                }
                order.markFailed();
                return new CheckoutResult(orderNo, order.getStatus(),
                        (result != null ? result.status() : PaymentStatus.DONE),
                        "재고가 부족해 결제를 취소 처리했습니다. 승인된 카드는 자동으로 취소됩니다.");
            }
        } else if (result.isUnknown()) {
            // 미확정: 카드 결과를 확정할 수 없다 → 선점한 포인트를 복원(보상)하고 주문은 PAYMENT_IN_PROGRESS로 유지.
            if (pointAmount > 0) {
                pointService.restore(order.getUserId(), pointAmount, orderNo);
            }
        } else {
            // 명시적 거절(ABORTED): 선점한 포인트를 복원(보상)하고, 재시도를 위해 주문을 PENDING_PAYMENT로 복귀.
            if (pointAmount > 0) {
                pointService.restore(order.getUserId(), pointAmount, orderNo);
            }
            order.revertToPending();
        }

        // 전액 포인트 결제는 PG 결과가 없으므로 DONE으로 간주해 응답한다.
        PaymentStatus paymentStatus = (result != null) ? result.status() : PaymentStatus.DONE;
        String message = (result != null) ? result.message() : "포인트 전액 결제 완료";
        return new CheckoutResult(orderNo, order.getStatus(), paymentStatus, message);
    }
}
