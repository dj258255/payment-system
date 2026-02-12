package com.beomsu.pay.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /** 멱등 판정: 같은 주문에 대해 같은 유형(USE/RESTORE/EARN) 이력이 이미 있는지. */
    boolean existsByOrderNoAndType(String orderNo, PointHistoryType type);

    /** 최근 포인트 이력 — 최신순. 잔액 조회 화면에 함께 싣는다. */
    List<PointHistory> findTop20ByUserIdOrderByIdDesc(long userId);

    /** 같은 주문·유형의 금액 합계. 이력이 없으면 0. 환불 가능 포인트 계산에 쓴다. */
    @Query("select coalesce(sum(h.amount),0) from PointHistory h where h.orderNo = :orderNo and h.type = :type")
    long sumAmountByOrderNoAndType(@Param("orderNo") String orderNo, @Param("type") PointHistoryType type);
}
