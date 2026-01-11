package com.beomsu.pay.fraud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** 상태별 심사 항목 전건 — 기동 시 블랙리스트 재적재(FraudBlacklistReloader)가 REJECTED 전건을 되읽는다. */
    List<FraudReview> findByStatus(FraudReviewStatus status);

    /** 어드민 관측용 — 상태별 심사 항목 페이지(기본 PENDING = 미결 건). 전건 로딩 방지 위해 페이지 단위. */
    Page<FraudReview> findByStatus(FraudReviewStatus status, Pageable pageable);
}
