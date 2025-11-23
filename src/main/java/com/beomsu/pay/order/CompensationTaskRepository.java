package com.beomsu.pay.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {

    /** 재시도 도래분 — 주어진 상태이면서 nextAttemptAt이 임계 시각 이전인 태스크. */
    List<CompensationTask> findByStatusAndNextAttemptAtBefore(CompensationStatus status, Instant threshold);
}
