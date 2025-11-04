package com.beomsu.pay.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
}
