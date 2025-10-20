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
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(PaymentCanceledEvent.class, ce -> {
            assertThat(ce.fullyCanceled()).isFalse();
            assertThat(ce.cancelAmount()).isEqualTo(3_000);
        });
    }
}
