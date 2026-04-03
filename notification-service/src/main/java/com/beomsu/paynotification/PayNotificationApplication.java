package com.beomsu.paynotification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 알림 서비스 — pay 모놀리스에서 추출된 두 번째 서비스.
 *
 * <p>결제 완료 이벤트를 Kafka로 구독해(컨슈머 그룹 {@code notification-service}) 알림을 발송한다.
 * 인프로세스 시절부터 갖고 있던 두 방어선이 여기서 실전이 된다:
 * <ul>
 *   <li><b>멱등 소비</b>(ProcessedEvent, (eventKey, consumer) 유니크) — Kafka at-least-once
 *       재배달에도 알림은 1회만 나간다.
 *   <li><b>DLQ</b>(DeadLetter) — 발송 실패를 삼켜 격리하고 어드민이 재처리한다. 실패가
 *       오프셋 진행을 막지 않는다.
 * </ul>
 * DB는 전용 {@code pay_notification} 스키마를 쓴다.
 */
@SpringBootApplication
public class PayNotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayNotificationApplication.class, args);
    }
}
