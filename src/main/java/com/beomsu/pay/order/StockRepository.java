package com.beomsu.pay.order;

import org.springframework.data.jpa.repository.JpaRepository;

interface StockRepository extends JpaRepository<Stock, Long> {
}
