package com.beomsu.pay.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);
}
