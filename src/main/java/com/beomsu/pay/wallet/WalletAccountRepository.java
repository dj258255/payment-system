package com.beomsu.pay.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {
}
