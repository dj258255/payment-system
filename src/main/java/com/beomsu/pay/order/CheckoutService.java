package com.beomsu.pay.order;

import com.beomsu.pay.payment.ApprovalOutcome;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.queue.QueueService;
import com.beomsu.pay.shared.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 체크아웃(주문 생성 + 결제 승인 오케스트레이션) 애플리케이션 서비스 — order 모듈의 공개 진입점.
 *
 * <p>금액 위변조 검증은 total_amount의 소유자인 주문이 담당하고(이 모듈의 핵심), 실제 PG 승인은
 * payment 모듈({@link PaymentService})에 위임한다. 재고 차감은 ADR-003에 따라 <b>승인 성공 시점</b>에 한다.
 *
 * <p><b>체크아웃 사가(ADR-007)</b>: {@code confirm}은 클래스가 아니라 오케스트레이터 한 메서드로,
 * 예약({@link CheckoutTx#reserve}, tx) → PG 승인({@link PaymentService#pgApprove}, <b>tx 밖</b>) →
 * 확정({@link CheckoutTx#settle}, tx) 3단계다. PG 외부 콜 동안 DB 커넥션을 붙잡지 않아, 느린 PG가
 * 커넥션 풀을 마르게 하지 않는다. 클래스 레벨 {@code @Transactional}을 떼고 각 단계만 트랜잭션 경계로 둔다.
 */
@Service
public class CheckoutService {

    private final PaymentService paymentService;
    private final CheckoutTx checkoutTx;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final QueueService queueService;
    private final List<Long> gateProductIds;
    private final String gateEventId;

    public CheckoutService(PaymentService paymentService,
                           CheckoutTx checkoutTx,
                           OrderRepository orderRepository,
                           ProductRepository productRepository,
                           QueueService queueService,
                           @Value("${app.queue.gate.product-ids:}") List<Long> gateProductIds,
                           @Value("${app.queue.gate.event-id:drop}") String gateEventId) {
        this.paymentService = paymentService;
        this.checkoutTx = checkoutTx;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.queueService = queueService;
        this.gateProductIds = gateProductIds;
        this.gateEventId = gateEventId;
    }

    /**
     * 주문 생성 — totalAmount를 확정 저장한다(이후 금액 위변조 검증의 기준값). 재고는 차감하지 않는다.
     *
     * <p>가격은 클라이언트가 보낸 값이 아니라 서버의 {@link Product} 카탈로그에서 조회한다.
     * 이래야 금액 위변조 검증의 기준값 자체를 신뢰할 수 있다.
     */
    @Transactional
    public CreateOrderResult createOrder(long userId, List<OrderLine> lines) {
        // 대기열 게이트(옵트인): 이벤트 상품만 서버가 대기열 입장을 강제한다(권고→강제).
        // 게이트 목록이 비어 있으면(기본) 이 블록은 스트림 한 번 안 돌고 건너뛴다 — 대상 없으면 무비용,
        // 기존 주문 경로는 100% 불변. 주문 생성이 서버 최초 쓰기 지점이라 여기서 막아야
        // DB(주문 INSERT)·이후 결제까지 아무 비용이 들지 않는다.
        if (!gateProductIds.isEmpty()
                && lines.stream().anyMatch(l -> gateProductIds.contains(l.productId()))
                && !queueService.hasEntryPass(gateEventId, String.valueOf(userId))) {
            throw new OrderException("QUEUE_PASS_REQUIRED", "선착순 대기열 입장 후 주문할 수 있습니다.");
        }
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
        // 0. 음수 방어 — 음수 pointAmount로 검증 우회·오버플로를 시도할 수 없게 한다. (DB 없음)
        if (pointAmount < 0 || cardAmount.amount() < 0) {
            throw new OrderException("INVALID_REQUEST", "결제 금액은 음수일 수 없습니다.");
        }

        // Phase 1 (tx) — 예약: 검증·주문 상태 전이(이중지불 잠금)·포인트 선점·결제 IN_PROGRESS 적재.
        // 커밋 후 DB 커넥션을 반납한다.
        CheckoutTx.Reservation reservation =
                checkoutTx.reserve(orderNo, paymentKey, cardAmount, pointAmount, authenticatedUserId);

        // Phase 2 (tx 밖) — PG 승인: 외부 HTTP 콜을 트랜잭션 밖에서 한다. 이 동안 DB 커넥션 0개 점유
        // → 느린 PG가 커넥션 풀을 마르게 하지 않는다(ADR-007). 전액 포인트면 PG 콜을 생략한다.
        ApprovalOutcome outcome = (cardAmount.amount() > 0)
                ? paymentService.pgApprove(orderNo, paymentKey, cardAmount)
                : null;

        // Phase 3 (tx) — 확정/보상: PG 결과를 결제·주문에 반영하고 재고 차감/보상까지 마친다.
        return checkoutTx.settle(orderNo, reservation.paymentId(), cardAmount, pointAmount, outcome);
    }
}
