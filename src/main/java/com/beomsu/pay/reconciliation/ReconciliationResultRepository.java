package com.beomsu.pay.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    /** 어드민 관측용 — 상태별 대사 결과 페이지(운영이 PENDING 예외 큐를 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<ReconciliationResult> findByStatus(ReconStatus status, Pageable pageable);

    /** SLO 게이지용 — 상태별 대사 결과 건수(운영이 PENDING 적체를 관측). */
    long countByStatus(ReconStatus status);

    /** 재실행 멱등: 그 거래일 판정을 지우고 다시 쓴다. 같은 파일을 두 번 올려도 큐가 늘지 않는다. */
    void deleteByTradeDate(LocalDate tradeDate);
}
