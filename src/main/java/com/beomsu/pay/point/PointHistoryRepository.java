package com.beomsu.pay.point;

import org.springframework.data.jpa.repository.JpaRepository;

interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /** 멱등 판정: 같은 주문에 대해 같은 유형(USE/RESTORE) 이력이 이미 있는지. */
    boolean existsByOrderNoAndType(String orderNo, PointHistoryType type);
}
