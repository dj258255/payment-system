package com.beomsu.pay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    boolean existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
            String txType, String sourceType, long sourceId, int sourceSeq);

    /** 최근 원장 트랜잭션 — 감사용. Top50으로 상한. */
    List<LedgerTransaction> findTop50ByOrderByIdDesc();

    /** 타임라인 조립용(ADR-011). 원장은 주문번호가 아니라 원천(결제 등)으로 묶인다. */
    List<LedgerTransaction> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, long sourceId);
}
