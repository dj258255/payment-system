package com.beomsu.pay.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {
}
