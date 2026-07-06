package com.beomsu.pay.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** 상태별 심사 항목(운영 관측용, 기본은 PENDING = 미결 건). */
    List<FraudReview> findByStatus(FraudReviewStatus status);
}
