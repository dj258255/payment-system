package com.beomsu.pay.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface InternalRecordRepository extends JpaRepository<InternalRecord, Long> {

    /** 적재 멱등성: 같은 주문이 이미 내부 기록으로 쌓였는지 확인. */
    boolean existsByOrderNo(String orderNo);

    /** 취소 반영: 그 주문의 내부 기록을 찾아 기대치를 낮춘다. */
    Optional<InternalRecord> findByOrderNo(String orderNo);

    /** 대사 범위: 그 거래일의 내부 기록만. 전체를 비교하면 지난 날짜가 전부 불일치로 쏟아진다. */
    List<InternalRecord> findByTradeDate(LocalDate tradeDate);
}
