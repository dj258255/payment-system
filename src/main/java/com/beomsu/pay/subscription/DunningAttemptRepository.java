package com.beomsu.pay.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface DunningAttemptRepository extends JpaRepository<DunningAttempt, Long> {

    /** 재시도 소진 판정·attemptNo 계산용: 해당 구독의 특정 결과(주로 SOFT_DECLINE) 시도 횟수. */
    int countBySubscriptionIdAndResult(Long subscriptionId, BillingResult result);

    /** 청구 이력 조회 — 구독의 청구 시도를 순서대로(시도번호 오름차순). */
    List<DunningAttempt> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
}
