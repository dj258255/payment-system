package com.beomsu.pay.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

interface DunningAttemptRepository extends JpaRepository<DunningAttempt, Long> {

    /** 재시도 소진 판정·attemptNo 계산용: 해당 구독의 특정 결과(주로 SOFT_DECLINE) 시도 횟수. */
    int countBySubscriptionIdAndResult(Long subscriptionId, BillingResult result);
}
