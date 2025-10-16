package com.beomsu.pay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    boolean existsByTxTypeAndSourceTypeAndSourceId(String txType, String sourceType, long sourceId);
}
