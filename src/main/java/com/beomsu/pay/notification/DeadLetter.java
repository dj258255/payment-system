package com.beomsu.pay.notification;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 죽은 메시지(Dead Letter). 컨슈머 처리가 실패한 이벤트를 격리해, 리스너가 계속 터지며
 * 파티션을 막는 것을 방지한다. 운영/배치가 여기서 재처리한다.
 */
@Entity
@Table(name = "dead_letters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 200)
    private String eventKey;

    @Column(length = 1000)
    private String failReason;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant createdAt;

    private DeadLetter(String eventType, String eventKey, String failReason) {
        this.eventType = eventType;
        this.eventKey = eventKey;
        this.failReason = failReason;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    static DeadLetter of(String eventType, String eventKey, String failReason) {
        return new DeadLetter(eventType, eventKey, failReason);
    }
}
