package com.beomsu.pay.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

interface InternalRecordRepository extends JpaRepository<InternalRecord, Long> {

    /** 적재 멱등성: 같은 주문이 이미 내부 기록으로 쌓였는지 확인. */
    boolean existsByOrderNo(String orderNo);
}
