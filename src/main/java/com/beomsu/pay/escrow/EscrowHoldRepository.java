package com.beomsu.pay.escrow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface EscrowHoldRepository extends JpaRepository<EscrowHold, Long> {

    /** 주문당 1홀드 — orderNo로 조회. */
    Optional<EscrowHold> findByOrderNo(String orderNo);

    /** 자동 구매확정 도래분 — 주어진 상태이면서 autoReleaseAt이 임계 시각 이전인 홀드. */
    List<EscrowHold> findByStatusAndAutoReleaseAtBefore(EscrowStatus status, Instant threshold);
}
