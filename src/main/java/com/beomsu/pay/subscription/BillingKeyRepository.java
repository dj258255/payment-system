package com.beomsu.pay.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    // billingKey는 암호화(비결정적)라 값으로 직접 조회할 수 없다 — 결정적 블라인드 인덱스로 조회한다.
    Optional<BillingKey> findByBillingKeyIndex(String billingKeyIndex);
}
