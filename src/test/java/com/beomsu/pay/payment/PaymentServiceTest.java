package com.beomsu.pay.payment;

import com.beomsu.pay.payment.pg.FakePgClient;
import com.beomsu.pay.payment.pg.PgApproveResult;
import com.beomsu.pay.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    private PaymentRepository repository;
    private ApplicationEventPublisher events;
    private FakePgClient pg;
    private SimpleMeterRegistry meterRegistry;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        events = mock(ApplicationEventPublisher.class);
        pg = new FakePgClient();
        meterRegistry = new SimpleMeterRegistry();
        when(repository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PaymentService(repository, pg, events, meterRegistry);
    }

    @Test
    @DisplayName("승인 성공 시 DONE + PaymentConfirmedEvent 발행 + 성공 메트릭 증가")
    void confirmSuccess() {
        pg.setNextResult(PgApproveResult.success("CARD"));

        ConfirmResult result = service.confirm("order-1", "pk-1", Money.of(10_000));

        assertThat(result.isApproved()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(result.method()).isEqualTo("CARD");
        verify(events).publishEvent(any(PaymentConfirmedEvent.class));
        // 승인 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(repository, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.DONE);
        // 관측성: 결과별 카운터가 증가한다 (Grafana 결제 성공률의 소스)
        assertThat(meterRegistry.counter("payment.confirm", "outcome", "success").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("타임아웃 시 UNKNOWN + 이벤트 미발행 (실패로 단정하지 않음)")
    void confirmTimeout() {
        pg.setNextResult(PgApproveResult.timeout("PG 응답 없음"));

        ConfirmResult result = service.confirm("order-2", "pk-2", Money.of(10_000));

        assertThat(result.isUnknown()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("명시적 거절 시 ABORTED + 이벤트 미발행")
    void confirmFailed() {
        pg.setNextResult(PgApproveResult.failed("잔액부족"));

        ConfirmResult result = service.confirm("order-3", "pk-3", Money.of(10_000));

        assertThat(result.status()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(result.message()).isEqualTo("잔액부족");
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("부분취소 시 PaymentCanceledEvent 발행 (fullyCanceled=false)")
    void cancelPartial() {
        Payment done = Payment.initiate("order-4", Money.of(10_000));
        done.startApproval("pk-4");
        done.approve("CARD");
        when(repository.findById(1L)).thenReturn(Optional.of(done));

        service.cancel(1L, Money.of(3_000), "부분 변심");

        assertThat(done.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        // 취소 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(done);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(PaymentCanceledEvent.class, ce -> {
            assertThat(ce.fullyCanceled()).isFalse();
            assertThat(ce.cancelAmount()).isEqualTo(3_000);
        });
    }

    @Test
    @DisplayName("주문번호 취소 시 취소 상태 전이가 명시 saveAndFlush로 영속된다")
    void cancelByOrderNoPersistsWithSave() {
        Payment done = Payment.initiate("order-5", Money.of(10_000));
        done.startApproval("pk-5");
        done.approve("CARD");
        when(repository.findFirstByOrderNoAndStatusIn(any(), any()))
                .thenReturn(Optional.of(done));

        service.cancelByOrderNo("order-5", Money.of(10_000), "전액 변심");

        assertThat(done.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(repository).saveAndFlush(done);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(PaymentCanceledEvent.class,
                ce -> assertThat(ce.fullyCanceled()).isTrue());
    }

    // --- 조회(read-only) 지원 ---

    @Test
    @DisplayName("orderNoOf: 결제의 주문번호 반환 / 없으면 empty")
    void orderNoOf() {
        Payment done = Payment.initiate("order-6", Money.of(10_000));
        when(repository.findById(1L)).thenReturn(Optional.of(done));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.orderNoOf(1L)).contains("order-6");
        assertThat(service.orderNoOf(2L)).isEmpty();
    }

    @Test
    @DisplayName("getDetail: 상태/이력 매핑 + 취소 전이만 cancels로 투영")
    void getDetailMapsHistoryAndCancels() {
        Payment payment = Payment.initiate("order-7", Money.of(10_000));
        payment.startApproval("pk-7");        // READY → IN_PROGRESS
        payment.approve("CARD");              // IN_PROGRESS → DONE
        payment.cancel(Money.of(3_000), TriggeredBy.USER, "부분 변심"); // DONE → PARTIAL_CANCELED
        // id는 DB 생성분(persist된 엔티티)이라 단위 테스트에선 직접 심는다.
        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(payment));

        PaymentDetailView view = service.getDetail(1L).orElseThrow();

        assertThat(view.orderNo()).isEqualTo("order-7");
        assertThat(view.status()).isEqualTo("PARTIAL_CANCELED");
        assertThat(view.amount()).isEqualTo(10_000);
        assertThat(view.balanceAmount()).isEqualTo(7_000);
        // history: 세 번의 전이가 모두 실린다.
        assertThat(view.history()).extracting(PaymentHistoryView::to)
                .containsExactly("IN_PROGRESS", "DONE", "PARTIAL_CANCELED");
        // cancels: 취소 전이(→ PARTIAL_CANCELED)만 투영된다.
        assertThat(view.cancels()).singleElement().satisfies(c -> {
            assertThat(c.to()).isEqualTo("PARTIAL_CANCELED");
            assertThat(c.reason()).isEqualTo("부분 변심");
        });
    }

    @Test
    @DisplayName("getDetail: 없는 결제는 empty")
    void getDetailEmpty() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.getDetail(9L)).isEmpty();
    }

    @Test
    @DisplayName("paymentStatusByOrderNo: 최신 결제 상태 반환 / 결제 없으면 empty")
    void paymentStatusByOrderNo() {
        Payment done = Payment.initiate("order-8", Money.of(10_000));
        done.startApproval("pk-8");
        done.approve("CARD");
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-8"))
                .thenReturn(Optional.of(done));
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-none"))
                .thenReturn(Optional.empty());

        assertThat(service.paymentStatusByOrderNo("order-8")).contains("DONE");
        assertThat(service.paymentStatusByOrderNo("order-none")).isEmpty();
    }
}
