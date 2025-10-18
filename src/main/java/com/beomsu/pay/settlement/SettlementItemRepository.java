package com.beomsu.pay.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    /** 적재 멱등성: 같은 결제가 이미 정산 항목으로 쌓였는지 확인. */
    boolean existsByPaymentId(long paymentId);

    /** 배치 집계 대상: 특정 날짜의 특정 상태(PENDING) 항목들. */
    List<SettlementItem> findByStatusAndConfirmedDate(SettlementItemStatus status, LocalDate confirmedDate);
}
