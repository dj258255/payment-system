package com.beomsu.pay.order;

import com.beomsu.pay.payment.ApprovalOutcome;
import com.beomsu.pay.payment.ConfirmResult;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.payment.PaymentStatus;
import com.beomsu.pay.point.PointService;
import com.beomsu.pay.queue.QueueService;
import com.beomsu.pay.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 체크아웃 사가(ADR-007) 테스트. 실제 {@link CheckoutTx}(예약·확정 트랜잭션 경계)를 목 리포지토리로
 * 주입해 3단계 사가 전체 동작을 검증한다 — PG 콜은 {@code pgApprove}(트랜잭션 밖), 결과 반영은
 * {@code applyResult}로 목킹한다. 사가 재작성 후에도 기존 동작(성공·미확정·거절·재고부족 보상·복합결제)이
 * 그대로 유지됨을 같은 단언으로 확인한다.
 */
class CheckoutServiceTest {

    private static final String GATE_EVENT = "drop";

    private PaymentService paymentService;
    private OrderRepository orderRepository;
    private StockDeductionService stockDeductionService;
    private ProductRepository productRepository;
    private PointService pointService;
    private CompensationService compensationService;
    private QueueService queueService;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        orderRepository = mock(OrderRepository.class);
        stockDeductionService = mock(StockDeductionService.class);
        productRepository = mock(ProductRepository.class);
        pointService = mock(PointService.class);
        compensationService = mock(CompensationService.class);
        queueService = mock(QueueService.class);
        when(stockDeductionService.tryDeduct(anyLong(), anyInt())).thenReturn(true);
        service = serviceWithGate(List.of());
    }

    private CheckoutService serviceWithGate(List<Long> gateProductIds) {
        CheckoutTx checkoutTx = new CheckoutTx(paymentService, orderRepository,
                stockDeductionService, pointService, compensationService);
        return new CheckoutService(paymentService, checkoutTx, orderRepository,
                productRepository, queueService, gateProductIds, GATE_EVENT);
    }

    private Order orderOf(long productId, int quantity) {
        Order order = Order.create(1L, List.of(
                OrderItem.of(productId, "상품A", 10_000, quantity)));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        return order;
    }

    private ConfirmResult approved() {
        return new ConfirmResult(123L, PaymentStatus.DONE, "CARD", "승인 완료");
    }

    /** 카드 승인 성공 경로 스텁 — beginApproval→id, pgApprove→SUCCESS, applyResult→DONE. */
    private void cardApproved() {
        when(paymentService.beginApproval(anyString(), anyString(), any(Money.class))).thenReturn(123L);
        when(paymentService.pgApprove(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ApprovalOutcome(ApprovalOutcome.Result.SUCCESS, "CARD", null));
        when(paymentService.applyResult(anyLong(), any(ApprovalOutcome.class))).thenReturn(approved());
    }

    /** 카드 결과가 finalStatus(UNKNOWN/ABORTED 등)로 확정되는 경로 스텁. */
    private void cardResolvesTo(PaymentStatus finalStatus, String msg) {
        ApprovalOutcome.Result pg = switch (finalStatus) {
            case DONE -> ApprovalOutcome.Result.SUCCESS;
            case UNKNOWN -> ApprovalOutcome.Result.TIMEOUT;
            default -> ApprovalOutcome.Result.FAILED;
        };
        when(paymentService.beginApproval(anyString(), anyString(), any(Money.class))).thenReturn(123L);
        when(paymentService.pgApprove(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ApprovalOutcome(pg, null, msg));
        when(paymentService.applyResult(anyLong(), any(ApprovalOutcome.class)))
                .thenReturn(new ConfirmResult(123L, finalStatus, null, msg));
    }

    @Test
    @DisplayName("승인 성공 시 재고를 차감하고 주문을 PAID로 만든다 (ADR-003)")
    void confirmApprovedDeductsStockAndMarksPaid() {
        Order order = orderOf(100L, 2);
        cardApproved();

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockDeductionService).tryDeduct(100L, 2);
        verify(orderRepository, atLeastOnce()).saveAndFlush(order);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verifyNoInteractions(pointService);
        verifyNoInteractions(compensationService);
    }

    @Test
    @DisplayName("PG 승인은 트랜잭션 밖에서(pgApprove) 호출된다 — 사가 3단계 배선")
    void confirmCallsPgApproveOutsideTransaction() {
        Order order = orderOf(100L, 2);
        cardApproved();

        service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        // 예약(beginApproval) → PG(pgApprove, tx 밖) → 확정(applyResult) 순으로 배선됨.
        verify(paymentService).beginApproval(eq(order.getOrderNo()), eq("pk-1"), eq(Money.of(20_000)));
        verify(paymentService).pgApprove(eq(order.getOrderNo()), eq("pk-1"), eq(Money.of(20_000)));
        verify(paymentService).applyResult(eq(123L), any(ApprovalOutcome.class));
    }

    @Test
    @DisplayName("금액 불일치 시 AMOUNT_MISMATCH 예외 + PG 승인은 호출되지 않는다")
    void confirmAmountMismatchDoesNotCallPayment() {
        Order order = orderOf(100L, 2); // total 20,000

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(19_000), 0, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(paymentService, never()).pgApprove(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("미확정(UNKNOWN) 시 주문은 PAYMENT_IN_PROGRESS로 유지되고 재고는 차감되지 않는다")
    void confirmUnknownKeepsInProgress() {
        Order order = orderOf(100L, 2);
        cardResolvesTo(PaymentStatus.UNKNOWN, "확인 중");

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("복합결제 미확정(UNKNOWN): 포인트를 복원하지 않고 예약 유지한다 — 이후 복구 완결 시 자금 손실 방지")
    void confirmCompositeUnknownKeepsPointReserved() {
        Order order = orderOf(100L, 2); // total 20,000
        cardResolvesTo(PaymentStatus.UNKNOWN, "확인 중");

        // 카드 14,000 + 포인트 6,000
        service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);
        verify(pointService).use(1L, 6_000, order.getOrderNo());              // 예약은 함
        // 핵심: 미확정에선 포인트를 복원하지 않는다(결제가 실제 승인일 수 있어, 복구가 DONE으로 완결하면
        // 예약 포인트가 그대로 소비돼야 가맹점이 제대로 걷는다). 여기서 복원하면 완결 시 재소비가 없어 손실.
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("명시적 거절(ABORTED) 시 주문은 PENDING_PAYMENT로 복귀한다")
    void confirmAbortedRevertsToPending() {
        Order order = orderOf(100L, 2);
        cardResolvesTo(PaymentStatus.ABORTED, "잔액부족");

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.ABORTED);
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("승인 성공 + 재고 부족(순수 카드): 예외 없이 망취소 보상 태스크 적재 + 주문 FAILED")
    void confirmApprovedButOutOfStockEnqueuesCompensation() {
        Order order = orderOf(100L, 3); // total 30,000
        cardApproved();
        when(stockDeductionService.tryDeduct(100L, 3)).thenReturn(false);

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(30_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(orderRepository, atLeastOnce()).saveAndFlush(order);
        verify(compensationService).enqueueNetworkCancel(order.getOrderNo(), 30_000L,
                "재고 부족: 카드 승인 후 자동 망취소");
        verify(stockDeductionService, never()).restore(anyLong(), anyInt());
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString());
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("승인 성공 + 복합결제 재고 부족: 선점 포인트 복원 + 망취소 적재 + 주문 FAILED")
    void confirmCompositeOutOfStockRestoresPointAndEnqueues() {
        Order order = orderOf(100L, 2); // total 20,000
        cardApproved();
        when(stockDeductionService.tryDeduct(100L, 2)).thenReturn(false);

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(pointService).restore(1L, 6_000, order.getOrderNo());
        verify(compensationService).enqueueNetworkCancel(order.getOrderNo(), 14_000L,
                "재고 부족: 카드 승인 후 자동 망취소");
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("승인 성공 + 부분 차감 후 재고 부족: 앞서 차감된 아이템만 재고 원복")
    void confirmPartialDeductionRestoresDeductedItems() {
        Order order = Order.create(1L, List.of(
                OrderItem.of(10L, "상품A", 10_000, 1),
                OrderItem.of(20L, "상품B", 10_000, 1)));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        cardApproved();
        when(stockDeductionService.tryDeduct(10L, 1)).thenReturn(true);
        when(stockDeductionService.tryDeduct(20L, 1)).thenReturn(false);

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(stockDeductionService).restore(10L, 1);
        verify(stockDeductionService, never()).restore(20L, 1);
        verify(compensationService).enqueueNetworkCancel(order.getOrderNo(), 20_000L,
                "재고 부족: 카드 승인 후 자동 망취소");
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("주문이 없으면 ORDER_NOT_FOUND 예외")
    void confirmOrderNotFound() {
        when(orderRepository.findByOrderNo("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("missing", "pk-1", Money.of(20_000), 0, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_NOT_FOUND"));
    }

    // --- 복합결제(포인트+카드) ---

    @Test
    @DisplayName("복합결제 성공: 포인트 선점 + 카드 승인 + 재고 차감 + PAID")
    void compositePaymentSuccess() {
        Order order = orderOf(100L, 2); // total 20,000
        cardApproved();

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(pointService).use(1L, 6_000, order.getOrderNo());          // 포인트 선점(카드보다 먼저)
        verify(paymentService).pgApprove(eq(order.getOrderNo()), eq("pk-1"), eq(Money.of(14_000)));
        verify(stockDeductionService).tryDeduct(100L, 2);
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString());
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("복합결제 카드 실패(ABORTED): 선점 포인트를 복원(보상)하고 PENDING_PAYMENT로 복귀")
    void compositePaymentCardFailureRestoresPoint() {
        Order order = orderOf(100L, 2); // total 20,000
        cardResolvesTo(PaymentStatus.ABORTED, "잔액부족");

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        verify(pointService).use(1L, 6_000, order.getOrderNo());
        verify(pointService).restore(1L, 6_000, order.getOrderNo());       // 보상 트랜잭션
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("복합결제 금액 검증: 카드+포인트 합이 주문금액과 다르면 AMOUNT_MISMATCH")
    void compositePaymentAmountMismatch() {
        Order order = orderOf(100L, 2); // total 20,000

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 5_000, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(pointService, never()).use(anyLong(), anyLong(), anyString());
        verify(paymentService, never()).pgApprove(anyString(), anyString(), any(Money.class));
    }

    @Test
    @DisplayName("전액 포인트 결제(cardAmount=0): 카드 호출 없이 재고 차감 + PAID")
    void fullPointPayment() {
        Order order = orderOf(100L, 2); // total 20,000

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(0), 20_000, 1L);

        verify(pointService).use(1L, 20_000, order.getOrderNo());
        verify(paymentService, never()).pgApprove(anyString(), anyString(), any(Money.class));
        verify(paymentService, never()).beginApproval(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService).tryDeduct(100L, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("남의 주문을 결제하려 하면 ORDER_FORBIDDEN (IDOR 방지) — 검증·차감 전에 차단")
    void confirmRejectsNonOwner() {
        Order order = orderOf(100L, 2); // 주인은 userId 1

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(paymentService, never()).pgApprove(anyString(), anyString(), any(Money.class));
        verify(paymentService, never()).beginApproval(anyString(), anyString(), any(Money.class));
        verify(pointService, never()).use(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("음수 포인트/카드 금액은 거부한다 (검증 우회 방지)")
    void confirmRejectsNegativeAmounts() {
        Order order = orderOf(100L, 2);

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(25_000), -5_000, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));
        verify(paymentService, never()).pgApprove(anyString(), anyString(), any(Money.class));
    }

    @Test
    @DisplayName("주문 생성 시 가격은 서버 카탈로그에서 조회해 totalAmount를 확정한다")
    void createOrderUsesServerSidePrice() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(100L)).thenReturn(Optional.of(Product.of(100L, "상품A", 10_000)));
        when(productRepository.findById(200L)).thenReturn(Optional.of(Product.of(200L, "상품B", 5_000)));

        CreateOrderResult result = service.createOrder(1L, List.of(
                new OrderLine(100L, 2),
                new OrderLine(200L, 1)));

        assertThat(result.totalAmount()).isEqualTo(25_000);
        assertThat(result.orderNo()).isNotBlank();
        assertThat(result.expiresAt()).isNotNull();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("클라이언트가 가격을 조작할 방법이 없다 — OrderLine에는 가격 필드 자체가 없다")
    void clientCannotSupplyPrice() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(1L, List.of(new OrderLine(999L, 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("PRODUCT_NOT_FOUND"));
        verify(orderRepository, never()).save(any(Order.class));
    }

    // --- 대기열 게이트(옵트인): 게이트 상품은 입장권 없이 주문할 수 없다 ---

    @Test
    @DisplayName("게이트 상품 + 입장권 없음: QUEUE_PASS_REQUIRED — 카탈로그 조회·주문 저장 전에 차단")
    void gatedProductWithoutPassRejected() {
        CheckoutService gated = serviceWithGate(List.of(100L));
        when(queueService.hasEntryPass(GATE_EVENT, "1")).thenReturn(false);

        assertThatThrownBy(() -> gated.createOrder(1L, List.of(new OrderLine(100L, 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("QUEUE_PASS_REQUIRED"));

        verifyNoInteractions(productRepository);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("게이트 상품 + 입장권 있음: 정상 주문 생성")
    void gatedProductWithPassCreatesOrder() {
        CheckoutService gated = serviceWithGate(List.of(100L));
        when(queueService.hasEntryPass(GATE_EVENT, "1")).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(100L)).thenReturn(Optional.of(Product.of(100L, "한정판", 50_000)));

        CreateOrderResult result = gated.createOrder(1L, List.of(new OrderLine(100L, 1)));

        assertThat(result.totalAmount()).isEqualTo(50_000);
        verify(queueService).hasEntryPass(GATE_EVENT, "1");
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("게이트 활성이라도 비대상 상품 주문은 입장권 검증 없이 진행")
    void nonGatedProductSkipsPassCheck() {
        CheckoutService gated = serviceWithGate(List.of(999L));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(100L)).thenReturn(Optional.of(Product.of(100L, "상품A", 10_000)));

        gated.createOrder(1L, List.of(new OrderLine(100L, 1)));

        verify(queueService, never()).hasEntryPass(anyString(), anyString());
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("게이트 목록 빈 경우(기본): 대기열 검증 자체가 없다 — 기존 동작 100% 불변")
    void emptyGateListNeverChecksPass() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(100L)).thenReturn(Optional.of(Product.of(100L, "상품A", 10_000)));

        service.createOrder(1L, List.of(new OrderLine(100L, 1)));

        verifyNoInteractions(queueService);
        verify(orderRepository).save(any(Order.class));
    }
}
