package com.beomsu.pay.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    /** 만료 배치용: 특정 상태이면서 만료 예정 시각이 지난 주문(결제 미완료로 방치된 건). */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant now);

    /** 멈춘 사가 복구용: 특정 상태로 이 시각 이전부터 머물러 있는 주문(마지막 갱신 기준). */
    List<Order> findByStatusAndUpdatedAtBefore(OrderStatus status, Instant threshold);
}
