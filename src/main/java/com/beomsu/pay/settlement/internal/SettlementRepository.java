package com.beomsu.pay.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /** 배치 재실행 멱등성: 그 날짜 정산이 이미 만들어졌는지 확인. */
    boolean existsBySettlementDateAndCurrency(LocalDate settlementDate, String currency);

    /**
     * 판매자별 존재 검사. <b>{@code sellerId} 가 {@code null} 인 경우를 따로 다룬다</b> —
     * JPA 파생 질의는 {@code = null} 로 만들어져 아무것도 못 찾는다. 그러면 플랫폼 직판
     * 정산이 매번 새로 만들어져 지급이 두 배가 된다.
     */
    @Query("""
            select count(s) > 0 from Settlement s
            where s.settlementDate = :date and s.currency = :currency
              and (:sellerId is null and s.sellerId is null or s.sellerId = :sellerId)
            """)
    boolean existsFor(@Param("date") LocalDate date,
                      @Param("currency") String currency,
                      @Param("sellerId") Long sellerId);
}
