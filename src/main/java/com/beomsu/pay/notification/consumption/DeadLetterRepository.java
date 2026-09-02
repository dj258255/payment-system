package com.beomsu.pay.notification.consumption;

import org.springframework.data.jpa.repository.JpaRepository;

// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 의
// allowedDependencies 가 막는다.
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    // findAll(), findById(), delete() 는 JpaRepository가 제공 — 어드민 조회·재처리에 사용
}
