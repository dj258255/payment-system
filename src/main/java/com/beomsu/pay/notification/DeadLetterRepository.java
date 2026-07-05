package com.beomsu.pay.notification;

import org.springframework.data.jpa.repository.JpaRepository;

interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    // findAll(), findById(), delete() 는 JpaRepository가 제공 — 어드민 조회·재처리에 사용
}
