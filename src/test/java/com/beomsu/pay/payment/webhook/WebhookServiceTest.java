package com.beomsu.pay.payment.webhook;

import com.beomsu.pay.payment.PaymentRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookServiceTest {

    private static final String SECRET = "test-webhook-secret";

    private WebhookEventRepository repository;
    private PaymentRecoveryService recoveryService;
    private ApplicationEventPublisher events;
    private WebhookService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookEventRepository.class);
        recoveryService = mock(PaymentRecoveryService.class);
        events = mock(ApplicationEventPublisher.class);
        // 실제 HMAC 검증기 — sign()으로 유효 서명을 만들어 통과시킨다.
        now = Instant.now();
        WebhookSignatureVerifier verifier =
                new WebhookSignatureVerifier(SECRET, Clock.fixed(now, ZoneOffset.UTC));
        service = new WebhookService(repository, verifier, recoveryService, new ObjectMapper(), events);
        // save는 인자를 그대로 돌려준다.
        when(repository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String body(String eventId, String paymentKey) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PAYMENT_STATUS_CHANGED\","
                + "\"data\":{\"paymentKey\":\"" + paymentKey + "\"}}";
    }

    private String validSignature(String body) {
        long ts = now.getEpochSecond();
        return "t=" + ts + ",v1=" + WebhookSignatureVerifier.sign(SECRET, ts, body);
    }

    @Test
    @DisplayName("정상 수신하면 WebhookEvent를 RECEIVED로 저장한다")
    void receiveStoresEvent() {
        String body = body("evt-1", "pk-1");
        when(repository.findByExternalEventId("evt-1")).thenReturn(Optional.empty());

        WebhookEvent event = service.receive(validSignature(body), body);

        assertThat(event.getExternalEventId()).isEqualTo("evt-1");
        assertThat(event.getEventType()).isEqualTo("PAYMENT_STATUS_CHANGED");
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(event.getRawPayload()).isEqualTo(body);
        verify(repository, times(1)).save(any(WebhookEvent.class));
        // 신규 수신은 비동기 해석 트리거를 발행한다(빠른 200 후 별도 스레드가 해석).
        verify(events, times(1)).publishEvent(any(WebhookReceivedEvent.class));
    }

    @Test
    @DisplayName("중복 externalEventId면 새로 저장하지 않고 기존 이벤트를 반환한다(멱등, 트리거 미발행)")
    void duplicateIsIdempotentlySkipped() {
        String body = body("evt-1", "pk-1");
        WebhookEvent existing = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", body);
        // 첫 수신은 신규, 두 번째 수신은 이미 존재.
        when(repository.findByExternalEventId("evt-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        service.receive(validSignature(body), body); // 신규 저장 1회
        WebhookEvent second = service.receive(validSignature(body), body); // 멱등 스킵

        assertThat(second).isSameAs(existing);
        verify(repository, times(1)).save(any(WebhookEvent.class)); // 저장은 한 번뿐
        // 트리거는 신규 1건에만 발행 — 중복 재전송은 발행하지 않는다(원 수신의 아웃박스 발행이 재시도 보장).
        verify(events, times(1)).publishEvent(any(WebhookReceivedEvent.class));
    }

    @Test
    @DisplayName("비동기 리스너는 id로 이벤트를 다시 로드해 PG 조회로 해석한다")
    void listenerReloadsByIdAndProcesses() {
        WebhookEvent event = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", body("evt-1", "pk-1"));
        when(repository.findById(42L)).thenReturn(Optional.of(event));

        service.onWebhookReceived(new WebhookReceivedEvent(42L));

        verify(recoveryService).resolveByPaymentKey("pk-1");
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    @DisplayName("process는 페이로드를 믿지 않고 resolveByPaymentKey로 재검증한 뒤 PROCESSED로 마감한다")
    void processResolvesAndMarksProcessed() {
        WebhookEvent event = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", body("evt-1", "pk-1"));

        service.process(event);

        verify(recoveryService).resolveByPaymentKey("pk-1");
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolveByPaymentKey가 예외를 던지면 삼키지 않고 전파한다 — 아웃박스가 재시도(미완료로 남김)")
    void processPropagatesFailureForOutboxRetry() {
        WebhookEvent event = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", body("evt-1", "pk-1"));
        doThrow(new RuntimeException("PG 조회 실패")).when(recoveryService).resolveByPaymentKey("pk-1");

        // 예외 전파 → @ApplicationModuleListener 트랜잭션 롤백 → 발행 미완료 → 재시도.
        // 오염된 트랜잭션에 FAILED를 쓰려 하지 않으므로 상태는 저장되지 않는다(RECEIVED 유지).
        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PG 조회 실패");
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.RECEIVED);
    }

    @Test
    @DisplayName("paymentKey가 없으면 조회 대상이 아니므로 SKIPPED로 종결한다(정상 커밋)")
    void processSkipsWhenNoPaymentKey() {
        String noKeyBody = "{\"eventId\":\"evt-1\",\"eventType\":\"PAYMENT_STATUS_CHANGED\"}";
        WebhookEvent event = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", noKeyBody);

        service.process(event);

        verify(recoveryService, never()).resolveByPaymentKey(any());
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.SKIPPED);
    }
}
