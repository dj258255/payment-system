package com.beomsu.pay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    boolean existsByTxTypeAndSourceTypeAndSourceId(String txType, String sourceType, long sourceId);

    /** 최근 원장 트랜잭션 — 감사용. Top50으로 상한. */
    List<LedgerTransaction> findTop50ByOrderByIdDesc();
}
