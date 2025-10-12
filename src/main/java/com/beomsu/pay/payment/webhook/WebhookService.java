package com.beomsu.pay.payment.webhook;

import com.beomsu.pay.payment.PaymentRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹훅 수신·해석 서비스.
 *
 * <p><b>핵심 원칙</b>: 수신({@link #receive})은 서명 검증 · 멱등 저장 · 빠른 응답만 담당하고,
 * 페이로드의 상태를 신뢰하지 않는다. 실제 상태 확정({@link #process})은 페이로드가 아니라
 * {@link PaymentRecoveryService#resolveByPaymentKey}로 <b>PG 조회 API를 통해 재검증</b>한다.
 *
 * <p>{@link #receive}와 {@link #process}를 분리해 둔 것은 지금은 동기로 이어 호출하지만
 * 나중에 {@code process}만 {@code @Async}로 떼어내 "빠른 200 응답 후 비동기 해석"으로 전환하기 위함이다.
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository repository;
    private final WebhookSignatureVerifier signatureVerifier;
    private final PaymentRecoveryService paymentRecoveryService;
    private final ObjectMapper objectMapper;

    /**
     * 수신 후 해석까지 이어서 수행하는 진입점. 컨트롤러가 호출한다.
     * 새로 저장된 이벤트만 해석하고, 중복 수신(이미 저장됨)은 조용히 넘긴다.
     */
    public void handle(String signatureHeader, String rawBody) {
        WebhookEvent event = receive(signatureHeader, rawBody);
        if (event.getStatus() == WebhookEventStatus.RECEIVED) {
            process(event);
        }
    }

    /**
     * 서명 검증 → 멱등 저장. 원본만 저장하고 상태 해석은 하지 않는다.
     *
     * <p>서명 검증 실패 시 예외를 던진다(컨트롤러가 401). 이미 받은 이벤트면 저장하지 않고
     * 기존 이벤트를 반환한다(멱등) — UNIQUE 제약 위반도 잡아 동일하게 흡수한다.
     */
    @Transactional
    public WebhookEvent receive(String signatureHeader, String rawBody) {
        // 1. 서명 검증(실패 시 WebhookException → 컨트롤러 401)
        signatureVerifier.verify(signatureHeader, rawBody);

        // 2. 최소 필드 파싱
        JsonNode root = parse(rawBody);
        String externalEventId = text(root, "eventId");
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new WebhookException("INVALID_WEBHOOK_PAYLOAD", "eventId가 없습니다");
        }
        String eventType = text(root, "eventType");

        // 3. 멱등 수신 — 이미 있으면 그대로 반환(중복 재전송은 정상 동작)
        var existing = repository.findByExternalEventId(externalEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 4. 원본 저장 → 빠른 응답. 페이로드 상태는 믿지 않는다.
        try {
            return repository.save(WebhookEvent.received(externalEventId, eventType, rawBody));
        } catch (DataIntegrityViolationException e) {
            // 동시 수신으로 UNIQUE 제약 위반 → 멱등 처리(기존 이벤트 반환)
            return repository.findByExternalEventId(externalEventId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 페이로드를 믿지 않고 PG 조회로 실제 상태를 확정한다.
     * 성공이면 PROCESSED, 예외면 FAILED로 남기되(다음 주기 재처리 대상) 예외를 밖으로 던지지 않는다.
     */
    @Transactional
    public void process(WebhookEvent event) {
        try {
            String paymentKey = extractPaymentKey(event.getRawPayload());
            if (paymentKey == null || paymentKey.isBlank()) {
                event.markSkipped("paymentKey 없음 — 조회 대상 아님");
            } else {
                // 페이로드가 아니라 조회 API로 실상태 재검증
                paymentRecoveryService.resolveByPaymentKey(paymentKey);
                event.markProcessed();
            }
        } catch (Exception e) {
            // 한 건 실패가 수신 응답(200)을 막지 않게 한다. 다음 주기(폴링/대사)에서 재처리된다.
            log.warn("웹훅 처리 실패 externalEventId={} : {}", event.getExternalEventId(), e.getMessage());
            event.markFailed(e.getMessage());
        }
        repository.save(event);
    }

    /** paymentKey는 최상위 또는 data 하위 어디에 있어도 유연하게 찾는다. */
    private String extractPaymentKey(String rawBody) {
        JsonNode root = parse(rawBody);
        String top = text(root, "paymentKey");
        if (top != null) {
            return top;
        }
        JsonNode data = root.get("data");
        return data != null ? text(data, "paymentKey") : null;
    }

    private JsonNode parse(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new WebhookException("INVALID_WEBHOOK_PAYLOAD", "페이로드 파싱 실패: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }
}
