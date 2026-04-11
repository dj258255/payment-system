package com.beomsu.pay.notification;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventKeyAndConsumer(String eventKey, String consumer);
}
