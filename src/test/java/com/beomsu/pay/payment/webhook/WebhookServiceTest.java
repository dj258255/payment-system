package com.beomsu.pay.payment.webhook;

import com.beomsu.pay.payment.PaymentRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookServiceTest {

    private static final String SECRET = "test-webhook-secret";

    private WebhookEventRepository repository;
    private PaymentRecoveryService recoveryService;
    private WebhookService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookEventRepository.class);
        recoveryService = mock(PaymentRecoveryService.class);
        // 실제 HMAC 검증기 — sign()으로 유효 서명을 만들어 통과시킨다.
        now = Instant.now();
        WebhookSignatureVerifier verifier =
                new WebhookSignatureVerifier(SECRET, Clock.fixed(now, ZoneOffset.UTC));
        service = new WebhookService(repository, verifier, recoveryService, new ObjectMapper());
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
    }

    @Test
    @DisplayName("중복 externalEventId면 새로 저장하지 않고 기존 이벤트를 반환한다(멱등)")
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
    @DisplayName("resolveByPaymentKey가 예외를 던지면 FAILED로 남기고 예외를 밖으로 던지지 않는다")
    void processMarksFailedOnError() {
        WebhookEvent event = WebhookEvent.received("evt-1", "PAYMENT_STATUS_CHANGED", body("evt-1", "pk-1"));
        doThrow(new RuntimeException("PG 조회 실패")).when(recoveryService).resolveByPaymentKey("pk-1");

        assertThatCode(() -> service.process(event)).doesNotThrowAnyException();

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(event.getFailReason()).contains("PG 조회 실패");
    }
}
