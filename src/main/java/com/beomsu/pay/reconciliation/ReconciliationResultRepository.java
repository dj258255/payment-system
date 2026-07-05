package com.beomsu.pay.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    /** 어드민 관측용 — 상태별 대사 결과(운영이 PENDING 예외 큐를 조회). */
    List<ReconciliationResult> findByStatus(ReconStatus status);
}
