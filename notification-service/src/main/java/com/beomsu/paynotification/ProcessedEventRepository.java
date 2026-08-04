package com.beomsu.paynotification;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventKeyAndConsumer(String eventKey, String consumer);
}
