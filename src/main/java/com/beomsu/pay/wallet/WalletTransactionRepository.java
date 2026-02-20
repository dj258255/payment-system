package com.beomsu.pay.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** 최근 거래 이력(충전·사용·환불) — 최신순. 잔액 조회 화면에 함께 싣는다. */
    List<WalletTransaction> findTop20ByUserIdOrderByIdDesc(long userId);
}
