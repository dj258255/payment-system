package com.beomsu.pay.order;

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
        // 기본값: 재고 차감 성공. 재고 부족 케이스는 개별 테스트에서 false로 재정의한다.
        when(stockDeductionService.tryDeduct(anyLong(), anyInt())).thenReturn(true);
        // 기본: 게이트 목록 빈(비활성) — 대기열 강제 없는 기존 동작. 게이트 케이스는 gatedService()로.
        service = serviceWithGate(List.of());
    }

    private CheckoutService serviceWithGate(List<Long> gateProductIds) {
        return new CheckoutService(paymentService, orderRepository, stockDeductionService,
                productRepository, pointService, compensationService,
                queueService, gateProductIds, GATE_EVENT);
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

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockDeductionService).tryDeduct(100L, 2); // 조건부 UPDATE 전략(ADR-004), 예외 없는 boolean
        // 주문 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(orderRepository).saveAndFlush(order);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verifyNoInteractions(pointService); // pointAmount=0 → 포인트 경로는 완전히 우회
        verifyNoInteractions(compensationService); // 정상 차감이므로 보상 없음
    }

    @Test
    @DisplayName("금액 불일치 시 AMOUNT_MISMATCH 예외 + PG 승인은 호출되지 않는다")
    void confirmAmountMismatchDoesNotCallPayment() {
        Order order = orderOf(100L, 2); // total 20,000

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(19_000), 0, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT); // 상태 전이도 없음
        verify(paymentService, never()).confirm(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("미확정(UNKNOWN) 시 주문은 PAYMENT_IN_PROGRESS로 유지되고 재고는 차감되지 않는다")
    void confirmUnknownKeepsInProgress() {
        Order order = orderOf(100L, 2);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.UNKNOWN, null, "확인 중"));

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("명시적 거절(ABORTED) 시 주문은 PENDING_PAYMENT로 복귀한다")
    void confirmAbortedRevertsToPending() {
        Order order = orderOf(100L, 2);
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.ABORTED, null, "잔액부족"));

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.ABORTED);
        verify(stockDeductionService, never()).tryDeduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("승인 성공 + 재고 부족(순수 카드): 예외 없이 망취소 보상 태스크 적재 + 주문 FAILED")
    void confirmApprovedButOutOfStockEnqueuesCompensation() {
        Order order = orderOf(100L, 3); // total 30,000
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());
        when(stockDeductionService.tryDeduct(100L, 3)).thenReturn(false); // 품절 경합

        // 예외를 던지지 않고 결과를 반환한다(트랜잭션을 깨끗이 커밋시키기 위해).
        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(30_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        // 실패(FAILED) 상태 전이도 명시 saveAndFlush로 영속된다.
        verify(orderRepository).saveAndFlush(order);
        verify(compensationService).enqueueNetworkCancel(order.getOrderNo(), 30_000L,
                "재고 부족: 카드 승인 후 자동 망취소");
        verify(stockDeductionService, never()).restore(anyLong(), anyInt()); // 차감된 게 없어 원복 불필요
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString()); // 포인트 미사용
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("승인 성공 + 복합결제 재고 부족: 선점 포인트 복원 + 망취소 적재 + 주문 FAILED")
    void confirmCompositeOutOfStockRestoresPointAndEnqueues() {
        Order order = orderOf(100L, 2); // total 20,000
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());
        when(stockDeductionService.tryDeduct(100L, 2)).thenReturn(false);

        // 카드 14,000 + 포인트 6,000 = 20,000
        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(pointService).restore(1L, 6_000, order.getOrderNo());   // 선점 포인트 복원(내부·확실)
        verify(compensationService).enqueueNetworkCancel(order.getOrderNo(), 14_000L,
                "재고 부족: 카드 승인 후 자동 망취소");                 // 카드 승인액만 망취소
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("승인 성공 + 부분 차감 후 재고 부족: 앞서 차감된 아이템만 재고 원복")
    void confirmPartialDeductionRestoresDeductedItems() {
        // 두 항목 주문: 첫 항목 차감 성공(10), 둘째 항목 부족(20)
        Order order = Order.create(1L, List.of(
                OrderItem.of(10L, "상품A", 10_000, 1),
                OrderItem.of(20L, "상품B", 10_000, 1)));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());
        when(stockDeductionService.tryDeduct(10L, 1)).thenReturn(true);
        when(stockDeductionService.tryDeduct(20L, 1)).thenReturn(false);

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(stockDeductionService).restore(10L, 1);              // 부분 차감분 원복
        verify(stockDeductionService, never()).restore(20L, 1);     // 둘째는 차감 안 됨
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
        when(paymentService.confirm(anyString(), anyString(), any(Money.class))).thenReturn(approved());

        // 카드 14,000 + 포인트 6,000 = 20,000
        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 6_000, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(pointService).use(1L, 6_000, order.getOrderNo());          // 포인트 선점(카드보다 먼저)
        verify(paymentService).confirm(eq(order.getOrderNo()), eq("pk-1"), eq(Money.of(14_000)));
        verify(stockDeductionService).tryDeduct(100L, 2);
        verify(pointService, never()).restore(anyLong(), anyLong(), anyString()); // 성공이므로 보상 없음
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("복합결제 카드 실패(ABORTED): 선점 포인트를 복원(보상)하고 PENDING_PAYMENT로 복귀")
    void compositePaymentCardFailureRestoresPoint() {
        Order order = orderOf(100L, 2); // total 20,000
        when(paymentService.confirm(anyString(), anyString(), any(Money.class)))
                .thenReturn(new ConfirmResult(123L, PaymentStatus.ABORTED, null, "잔액부족"));

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

        // 카드 14,000 + 포인트 5,000 = 19,000 ≠ 20,000
        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(14_000), 5_000, 1L))
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

        CheckoutResult result = service.confirm(order.getOrderNo(), "pk-1", Money.of(0), 20_000, 1L);

        verify(pointService).use(1L, 20_000, order.getOrderNo());
        verify(paymentService, never()).confirm(anyString(), anyString(), any(Money.class));
        verify(stockDeductionService).tryDeduct(100L, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("남의 주문을 결제하려 하면 ORDER_FORBIDDEN (IDOR 방지) — 검증·차감 전에 차단")
    void confirmRejectsNonOwner() {
        Order order = orderOf(100L, 2); // 주인은 userId 1

        // userId 2가 주인이 1인 주문을 결제 시도
        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(20_000), 0, 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT); // 상태 전이 없음
        verifyNoInteractions(paymentService);
        verify(pointService, never()).use(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("음수 포인트/카드 금액은 거부한다 (검증 우회 방지)")
    void confirmRejectsNegativeAmounts() {
        Order order = orderOf(100L, 2);

        assertThatThrownBy(() -> service.confirm(order.getOrderNo(), "pk-1", Money.of(25_000), -5_000, 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));
        verifyNoInteractions(paymentService);
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

    // --- 대기열 게이트(옵트인): 게이트 상품은 입장권 없이 주문할 수 없다 ---

    @Test
    @DisplayName("게이트 상품 + 입장권 없음: QUEUE_PASS_REQUIRED — 카탈로그 조회·주문 저장 전에 차단")
    void gatedProductWithoutPassRejected() {
        CheckoutService gated = serviceWithGate(List.of(100L));
        when(queueService.hasEntryPass(GATE_EVENT, "1")).thenReturn(false);

        assertThatThrownBy(() -> gated.createOrder(1L, List.of(new OrderLine(100L, 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("QUEUE_PASS_REQUIRED"));

        // 서버 최초 쓰기 지점에서 막았다 — DB 조회/INSERT 비용 0.
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
        CheckoutService gated = serviceWithGate(List.of(999L));  // 게이트 대상은 999뿐
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

        service.createOrder(1L, List.of(new OrderLine(100L, 1)));   // 기본 service = 게이트 비활성

        verifyNoInteractions(queueService);                         // hasEntryPass never
        verify(orderRepository).save(any(Order.class));
    }
}
