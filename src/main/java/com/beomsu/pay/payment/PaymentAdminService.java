package com.beomsu.pay.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 미확정(UNKNOWN) 결제 운영 어드민 — 방치된 미확정 결제를 조회하고 수동 복구를 트리거한다.
 *
 * <p>{@link PaymentRecoveryService#recoverUnknownPayments()}는 스케줄러가 있지만
 * ({@code payment.recovery.enabled=true}), 운영이 장애 복구 직후 즉시 한 번 돌리고 싶을 때
 * 이 어드민으로 수동 실행한다. 실제 확정 로직은 복구 서비스에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentAdminService {

    private final PaymentRepository paymentRepository;
    private final PaymentRecoveryService recoveryService;

    /** 미확정(UNKNOWN) 결제 목록. */
    @Transactional(readOnly = true)
    public List<UnknownPaymentView> listUnknown() {
        return paymentRepository.findByStatus(PaymentStatus.UNKNOWN).stream()
                .map(p -> new UnknownPaymentView(p.getId(), p.getOrderNo(), p.getAmount(),
                        p.getStatus(), p.getRequestedAt()))
                .toList();
    }

    /** 미확정 결제 복구를 즉시 1회 실행한다. 반환값은 처리한 건수. */
    public int recover() {
        return recoveryService.recoverUnknownPayments();
    }
}
