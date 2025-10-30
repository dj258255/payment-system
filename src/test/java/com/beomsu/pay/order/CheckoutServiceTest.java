package com.beomsu.pay.order;

import com.beomsu.pay.payment.ConfirmResult;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.payment.PaymentStatus;
import com.beomsu.pay.point.PointService;
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

class CheckoutServiceTest {

    private PaymentService paymentService;
    private OrderRepository orderRepository;
    private StockDeductionService stockDeductionService;
    private ProductRepository productRepository;
    private PointService pointService;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        orderRepository = mock(OrderRepository.class);
        stockDeductionService = mock(StockDeductionService.class);
        productRepository = mock(ProductRepository.class);
        pointService = mock(PointService.class);
        service = new CheckoutService(paymentService, orderRepository, stockDeductionService,
                productRepository, pointService);
    }

    /** 10,000 x 2 = 20,000 짜리 단일 항목 주문. */
    private Order orderOf(long productId, int quantity) {
        Order order = Order.create(1L, List.of(
                OrderItem.of(productId, "상품A", 10_000, quantity)));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        return order;
    }

    private ConfirmResult approved() {
        return new ConfirmResult(123L, PaymentStatus.DONE, "CARD", "승인 완료");
    }

    @Test
    @DisplayName("승인 성공 시 재고를 차감하고 주문을 PAID로 만든다 (ADR-003)")
    void confirmApprovedDeductsStockAndMarksPaid() {
        Order order = orderOf(100L, 2);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockDeductionService).deductConditional(100L, 2); // 조건부 UPDATE 전략(ADR-004)
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verifyNoInteractions(pointService); // pointAmount=0 → 포인트 경로는 완전히 우회
    }

    @Test
    @DisplayName("금액 불일치 시 AMOUNT_MISMATCH 예외 + PG 승인은 호출되지 않는다")
    void confirmAmountMismatchDoesNotCallPayment() {
        Order order = orderOf(100L, 2); // total 20,000

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(19_000), 0))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT); // 상태 전이도 없음
        verify(paymentService, never()).confirm(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService, never()).deductConditional(anyLong(), anyInt());
    }

    @Test
    @DisplayName("미확정(UNKNOWN) 시 주문은 PAYMENT_IN_PROGRESS로 유지되고 재고는 차감되지 않는다")
    void confirmUnknownKeepsInProgress() {
        Order order = orderOf(100L, 2);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.UNKNOWN, null, "확인 중"));

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(stockDeductionService, never()).deductConditional(anyLong(), anyInt());
    }

    @Test
    @DisplayName("명시적 거절(ABORTED) 시 주문은 PENDING_PAYMENT로 복귀한다")
    void confirmAbortedRevertsToPending() {
        Order order = orderOf(100L, 2);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.ABORTED, null, "잔액부족"));

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.ABORTED);
        verify(stockDeductionService, never()).deductConditional(anyLong(), anyInt());
    }

    @Test
    @DisplayName("승인은 성공했으나 재고가 부족하면 OUT_OF_STOCK 예외 (Phase 2 보상 대상)")
    void confirmApprovedButOutOfStock() {
        Order order = orderOf(100L, 3);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());
        doThrow(OrderException.outOfStock(100L)).when(stockDeductionService).deductConditional(100L, 3);

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(30_000), 0))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("주문이 없으면 ORDER_NOT_FOUND 예외")
    void confirmOrderNotFound() {
        when(orderRepository.findByOrderNo("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("missing", "pk-1", Money.of(20_000), 0))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_NOT_FOUND"));
    }

    // --- 복합결제(포인트+카드) ---

    @Test
    @DisplayName("복합결제 성공: 포인트 선점 + 카드 승인 + 재고 차감 + PAID")
    void compositePaymentSuccess() {
        Order order = orderOf(100L, 2); // total 20,000
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());

        // 카드 14,000 + 포인트 6,000 = 20,000
        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(pointService).use(1L, 6_000, order.getOrderNo());          // 포인트 선점(카드보다 먼저)
        verify(paymentService).confirm(eq(order.getOrderNo()), eq("pk-1"), eq(Money.of(14_000)));
        verify(stockDeductionService).deductConditional(100L, 2);
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString()); // 성공이므로 보상 없음
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("복합결제 카드 실패(ABORTED): 선점 포인트를 복원(보상)하고 PENDING_PAYMENT로 복귀")
    void compositePaymentCardFailureRestoresPoint() {
        Order order = orderOf(100L, 2); // total 20,000
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.ABORTED, null, "잔액부족"));

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000);

        verify(pointService).use(1L, 6_000, order.getOrderNo());
        verify(pointService).restore(1L, 6_000, order.getOrderNo());       // 보상 트랜잭션
        verify(stockDeductionService, never()).deductConditional(anyLong(), anyInt());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("복합결제 금액 검증: 카드+포인트 합이 주문금액과 다르면 AMOUNT_MISMATCH")
    void compositePaymentAmountMismatch() {
        Order order = orderOf(100L, 2); // total 20,000

        // 카드 14,000 + 포인트 5,000 = 19,000 ≠ 20,000
        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 5_000))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT); // 상태 전이 없음
        verify(pointService, never()).use(anyLong(), anyLong(), anyString()); // 포인트 차감도 없음
        verify(paymentService, never()).confirm(anyString(), anyString(), any(Money.class));
    }

    @Test
    @DisplayName("전액 포인트 결제(cardAmount=0): 카드 호출 없이 재고 차감 + PAID")
    void fullPointPayment() {
        Order order = orderOf(100L, 2); // total 20,000

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(0), 20_000);

        verify(pointService).use(1L, 20_000, order.getOrderNo());
        verify(paymentService, never()).confirm(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService).deductConditional(100L, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
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

        // 클라이언트는 productId·quantity만 보냈고, 금액은 서버 가격(10,000·5,000)으로 계산됨
        assertThat(result.totalAmount()).isEqualTo(25_000);
        assertThat(result.orderNo()).isNotBlank();
        assertThat(result.expiresAt()).isNotNull();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("클라이언트가 가격을 조작할 방법이 없다 — OrderLine에는 가격 필드 자체가 없다")
    void clientCannotSupplyPrice() {
        // OrderLine(productId, quantity) — 컴파일 타임에 가격 주입이 불가능한 것이 방어의 핵심.
        // 서버가 카탈로그에 없는 상품이면 주문 생성 자체가 실패한다.
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(1L, List.of(new OrderLine(999L, 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("PRODUCT_NOT_FOUND"));
        verify(orderRepository, never()).save(any(Order.class));
    }
}
