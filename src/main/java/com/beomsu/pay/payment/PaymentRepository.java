package com.beomsu.pay.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    /** 복구 배치용: 특정 상태로 일정 시각 이전부터 머문 결제(미확정 방치 건). */
    List<Payment> findByStatusAndRequestedAtBefore(PaymentStatus status, Instant threshold);
}
