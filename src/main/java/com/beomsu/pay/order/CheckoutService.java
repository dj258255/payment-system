package com.beomsu.pay.order;

import com.beomsu.pay.payment.ConfirmResult;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final StockRepository stockRepository;
    private final ProductRepository productRepository;

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
     * 결제 승인. 서버 내부 처리 순서(10-API-스펙):
     * <ol>
     *   <li>주문 로드 (없으면 ORDER_NOT_FOUND)</li>
     *   <li>금액 위변조 검증 — 불일치 시 AMOUNT_MISMATCH (PG 호출 이전에 차단)</li>
     *   <li>주문 상태 조건부 전이 PENDING_PAYMENT → PAYMENT_IN_PROGRESS (이중 지불 차단)</li>
     *   <li>PG 승인 호출(payment 모듈 위임)</li>
     *   <li>결과 분기: 승인 성공 → 재고 차감 + PAID / 미확정 → 유지 / 거절 → PENDING_PAYMENT 복귀</li>
     * </ol>
     */
    public CheckoutResult confirm(String orderNo, String paymentKey, Money requestedAmount) {
        // 1. 주문 로드
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        // 2. 금액 위변조 검증 — order가 total_amount의 소유자이므로 검증도 여기서.
        //    불일치 시 여기서 예외가 나므로 PG 승인은 호출되지 않는다.
        order.verifyAmount(requestedAmount);

        // 3. 주문 상태 조건부 전이 (이중 지불 차단)
        order.startPayment();

        // 4. PG 승인 위임
        ConfirmResult result = paymentService.confirm(orderNo, paymentKey, requestedAmount);

        // 5. 결과 분기
        if (result.isApproved()) {
            // 재고 차감 (ADR-003: 승인 성공 시점 차감).
            // Phase 1은 차감 실패를 예외로 둔다 → Phase 2에서 망취소/보상 트랜잭션으로 승격.
            for (OrderItem item : order.getItems()) {
                Stock stock = stockRepository.findById(item.getProductId())
                        .orElseThrow(() -> OrderException.outOfStock(item.getProductId()));
                stock.deduct(item.getQuantity());
            }
            order.markPaid();
        } else if (result.isUnknown()) {
            // 미확정: 주문은 PAYMENT_IN_PROGRESS로 유지한다(복구 배치가 확정).
            // 상태 전이 없음.
        } else {
            // 명시적 거절(ABORTED): 재시도를 위해 주문을 PENDING_PAYMENT로 복귀시킨다.
            order.revertToPending();
        }

        return new CheckoutResult(orderNo, order.getStatus(), result.status(), result.message());
    }

    // NOTE: 주문 취소(cancel)는 Phase 2/3으로 미룬다.
    // 결제-주문 연결이 orderNo 기반이고 order가 paymentId를 보유하지 않으므로,
    // 취소 위임은 payment 조회 경로가 정리되는 Phase 2에서 구현한다.
    // (PAID → CANCELED 전이와 Order.cancel()은 미리 마련해 두었다.)
}
