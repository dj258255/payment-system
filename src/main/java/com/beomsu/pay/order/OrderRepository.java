package com.beomsu.pay.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    /** 만료 배치용: 특정 상태이면서 만료 예정 시각이 지난 주문(결제 미완료로 방치된 건). */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant now);
}
