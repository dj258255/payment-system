package com.beomsu.pay.payment;

import com.beomsu.pay.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentAdminServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentRecoveryService recoveryService;
    private PaymentAdminService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        recoveryService = mock(PaymentRecoveryService.class);
        service = new PaymentAdminService(paymentRepository, recoveryService);
    }

    private Payment unknownPayment() {
        Payment p = Payment.initiate("ord-1", Money.of(14_000));
        p.startApproval("pk-1");
        p.markUnknown("PG 타임아웃");
        return p;
    }

    @Test
    @DisplayName("listUnknown: UNKNOWN 결제를 뷰로 매핑한다")
    void listUnknownMapsToView() {
        Payment p = unknownPayment();
        when(paymentRepository.findByStatus(PaymentStatus.UNKNOWN)).thenReturn(List.of(p));

        List<UnknownPaymentView> views = service.listUnknown();

        assertThat(views).hasSize(1);
        UnknownPaymentView v = views.get(0);
        assertThat(v.orderNo()).isEqualTo("ord-1");
        assertThat(v.amount()).isEqualTo(14_000);
        assertThat(v.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(v.requestedAt()).isNotNull();
    }

    @Test
    @DisplayName("recover: 복구 서비스에 위임하고 처리 건수를 반환한다")
    void recoverDelegatesAndReturnsCount() {
        when(recoveryService.recoverUnknownPayments()).thenReturn(3);

        int recovered = service.recover();

        assertThat(recovered).isEqualTo(3);
        verify(recoveryService).recoverUnknownPayments();
    }
}
