package com.beomsu.pay.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 배치 청구 대상 조회 — 지정 상태이면서 다음 청구일이 today 이하인 구독. */
    List<Subscription> findByStatusAndNextBillingDateLessThanEqual(SubscriptionStatus status, LocalDate date);

    /** 배치 청구 대상 조회 — ACTIVE/IN_GRACE_PERIOD 등 여러 상태를 한 번에. */
    List<Subscription> findByStatusInAndNextBillingDateLessThanEqual(
            Collection<SubscriptionStatus> statuses, LocalDate date);
}
