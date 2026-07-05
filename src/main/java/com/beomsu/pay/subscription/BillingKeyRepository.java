package com.beomsu.pay.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    Optional<BillingKey> findByBillingKey(String billingKey);
}
