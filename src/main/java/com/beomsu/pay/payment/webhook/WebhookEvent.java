package com.beomsu.pay.payment.webhook;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 수신한 PG 웹훅 이벤트 원본 — 감사·멱등·재처리의 근거.
 *
 * <p>수신 시점에는 <b>원본 페이로드 저장</b>만 하고 상태 해석은 하지 않는다(페이로드를 신뢰하지 않음).
 * 이후 워커가 조회 API로 실상태를 재검증한 뒤 {@link #markProcessed()} 등으로 결과를 남긴다.
 * {@code externalEventId}에 UNIQUE 제약을 걸어 중복 재전송을 멱등하게 흡수한다.
 */
@Entity
@Table(
        name = "webhook_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_webhook_external_id", columnNames = "externalEventId")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PG가 부여한 이벤트 식별자 — 멱등 키(UNIQUE). */
    @Column(nullable = false, length = 100)
    private String externalEventId;

    @Column(length = 60)
    private String eventType;

    /** 원본 JSON 페이로드 전체(감사·재처리용). */
    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookEventStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    @Column(length = 500)
    private String failReason;

    private WebhookEvent(String externalEventId, String eventType, String rawPayload) {
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
        this.status = WebhookEventStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }

    /** 수신 직후 상태(RECEIVED)로 생성한다. */
    public static WebhookEvent received(String externalEventId, String eventType, String rawPayload) {
        return new WebhookEvent(externalEventId, eventType, rawPayload);
    }

    /** 조회 재검증까지 마친 정상 처리 완료. */
    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.failReason = null;
    }

    /** 처리 실패 — 사유를 남기고 다음 주기 재처리 대상이 된다. */
    public void markFailed(String reason) {
        this.status = WebhookEventStatus.FAILED;
        this.processedAt = Instant.now();
        this.failReason = reason;
    }

    /** 처리 대상이 아니라 건너뜀(예: paymentKey 없음). */
    public void markSkipped(String reason) {
        this.status = WebhookEventStatus.SKIPPED;
        this.processedAt = Instant.now();
        this.failReason = reason;
    }
}
