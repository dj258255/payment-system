package com.beomsu.pay.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ForceCancelRequestRepository extends JpaRepository<ForceCancelRequest, Long> {

    /** 어드민 목록용 — 상태별 강제취소 요청(운영이 REQUESTED 미결 건을 조회). */
    List<ForceCancelRequest> findByStatus(ForceCancelStatus status);
}
