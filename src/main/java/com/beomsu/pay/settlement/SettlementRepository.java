package com.beomsu.pay.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /** 배치 재실행 멱등성: 그 날짜 정산이 이미 만들어졌는지 확인. */
    boolean existsBySettlementDate(LocalDate settlementDate);
}
