package com.beomsu.pay.notification;

import org.springframework.data.jpa.repository.JpaRepository;

interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
}
