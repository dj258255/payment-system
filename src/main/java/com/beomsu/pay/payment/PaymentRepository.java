package com.beomsu.pay.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    /** 복구 배치용: 특정 상태로 일정 시각 이전부터 머문 결제(미확정 방치 건). */
    List<Payment> findByStatusAndRequestedAtBefore(PaymentStatus status, Instant threshold);

    /** 어드민 관측용 — 상태별 결제 목록(운영이 UNKNOWN 미확정 건을 조회). */
    List<Payment> findByStatus(PaymentStatus status);

    /** 취소용: 주문의 성공한(취소 가능한) 결제 1건. 재시도로 ABORTED 등이 섞여 있어도 성공 건만 고른다. */
    Optional<Payment> findFirstByOrderNoAndStatusIn(String orderNo, List<PaymentStatus> statuses);
}
