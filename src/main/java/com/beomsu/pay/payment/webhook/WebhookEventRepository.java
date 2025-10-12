package com.beomsu.pay.payment.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 웹훅 이벤트 저장소. 모듈 내부에서만 사용한다(package-private). */
interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** 멱등 수신 판정용 — 이미 받은 이벤트인지 externalEventId로 조회한다. */
    Optional<WebhookEvent> findByExternalEventId(String externalEventId);
}
