package com.beomsu.pay.payment.webhook;

import com.beomsu.pay.payment.PaymentRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹훅 수신·해석 서비스.
 *
 * <p><b>핵심 원칙</b>: 수신({@link #receive})은 서명 검증 · 멱등 저장 · 빠른 응답만 담당하고,
 * 페이로드의 상태를 신뢰하지 않는다. 실제 상태 확정({@link #process})은 페이로드가 아니라
 * {@link PaymentRecoveryService#resolveByPaymentKey}로 <b>PG 조회 API를 통해 재검증</b>한다.
 *
 * <p><b>빠른 200 + 비동기 해석</b>: PG는 웹훅에 <b>10초 내 2xx</b>를 요구하고, 못 주면 재전송을
 * 반복한다. 그런데 {@link #process}는 PG 조회 API를 <b>동기 호출</b>하므로, 수신 스레드에서 이어
 * 하면 그 네트워크 왕복이 응답 시간에 얹혀 10초를 넘길 위험이 있다. 그래서 {@link #receive}는
 * 신규 이벤트에 대해 {@link WebhookReceivedEvent}만 발행하고 즉시 반환하며, 실제 해석은 커밋 이후
 * {@link #onWebhookReceived}가 <b>별도 스레드</b>에서 수행한다. 발행은 Modulith 아웃박스에 수신
 * 트랜잭션과 함께 기록되므로, 해석 전에 앱이 죽어도 재기동 시 재발행된다({@code @Async}와 달리 유실 없음).
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookEventRepository repository;
    private final WebhookSignatureVerifier signatureVerifier;
    private final PaymentRecoveryService paymentRecoveryService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    /**
     * 컨트롤러 진입점 — 수신(서명 검증·멱등 저장)만 하고 즉시 반환한다. 실제 PG 조회 해석은
     * {@link #receive}가 발행한 {@link WebhookReceivedEvent}를 받아 비동기로 이뤄진다.
     *
     * <p><b>이 메서드가 트랜잭션 경계다</b>: {@code @ApplicationModuleListener}의 {@code AFTER_COMMIT}
     * 발화는 발행이 <b>커밋되는 트랜잭션 안</b>에서 일어나야 걸린다. {@link #receive}는 자기호출로
     * 진입하므로(프록시 미경유) 자체 {@code @Transactional}이 무시된다 — 그래서 진입점인 여기에
     * 트랜잭션을 열어, 발행이 이 트랜잭션에 실려 커밋되고 그 커밋에 비동기 해석이 걸리게 한다.
     */
    @Transactional
    public void handle(String signatureHeader, String rawBody) {
        receive(signatureHeader, rawBody);
    }

    /**
     * 서명 검증 → 멱등 저장 → (신규면) 비동기 해석 트리거 발행. 원본만 저장하고 상태 해석은 하지 않는다.
     *
     * <p>서명 검증 실패 시 예외를 던진다(컨트롤러가 401). 이미 받은 이벤트면 저장하지 않고
     * 기존 이벤트를 반환한다(멱등) — UNIQUE 제약 위반도 잡아 동일하게 흡수한다. 이 경우
     * 트리거를 발행하지 않는다(원 수신의 아웃박스 발행이 해석 재시도를 이미 보장).
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

        // 3. 멱등 수신 — 이미 있으면 그대로 반환(중복 재전송은 정상 동작, 트리거 미발행)
        var existing = repository.findByExternalEventId(externalEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 4. 원본 저장 → 비동기 해석 트리거 발행 → 빠른 응답. 페이로드 상태는 믿지 않는다.
        try {
            WebhookEvent saved = repository.save(WebhookEvent.received(externalEventId, eventType, rawBody));
            // 수신 트랜잭션 안에서 발행 → 아웃박스에 함께 커밋. 커밋 후 리스너가 별도 스레드로 해석.
            events.publishEvent(new WebhookReceivedEvent(saved.getId()));
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 동시 수신으로 UNIQUE 제약 위반 → 멱등 처리(기존 이벤트 반환, 트리거 미발행)
            return repository.findByExternalEventId(externalEventId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 비동기 해석 리스너 — 수신 커밋 이후 <b>별도 스레드</b>에서 PG 조회로 실제 상태를 확정한다.
     * Modulith 아웃박스가 발행을 영속화·재발행하므로, 앱이 해석 전에 죽어도 재기동 시 다시 처리된다.
     * id로 다시 로드해 최신 상태로 해석한다(발행 시점 엔티티가 아니라 현재 행).
     */
    @ApplicationModuleListener
    void onWebhookReceived(WebhookReceivedEvent event) {
        repository.findById(event.webhookEventId()).ifPresent(this::process);
    }

    /**
     * 페이로드를 믿지 않고 PG 조회로 실제 상태를 확정한다. 성공이면 PROCESSED로 마감한다.
     *
     * <p><b>실패는 삼키지 않고 전파한다</b>: 비동기 해석은 아웃박스({@link WebhookReceivedEvent})에
     * 실려 오므로, 여기서 예외를 던지면 Modulith가 발행을 <b>미완료로 남겨 재시도</b>한다(at-least-once).
     * 예외를 catch해 같은 트랜잭션에 FAILED를 쓰려 하면, PG 조회 예외가 이미 그 트랜잭션을
     * rollback-only로 오염시켜 그 write마저 커밋되지 않는다 — 그래서 "포기하고 FAILED" 대신
     * "재시도에 맡긴다". 조회 대상이 아닌 경우(paymentKey 없음)만 SKIPPED로 종결한다(정상 커밋).
     */
    @Transactional
    public void process(WebhookEvent event) {
        String paymentKey = extractPaymentKey(event.getRawPayload());
        if (paymentKey == null || paymentKey.isBlank()) {
            event.markSkipped("paymentKey 없음 — 조회 대상 아님");
            repository.save(event);
            return;
        }
        // 페이로드가 아니라 조회 API로 실상태 재검증. 실패 시 예외 전파 → 아웃박스가 재시도.
        paymentRecoveryService.resolveByPaymentKey(paymentKey);
        event.markProcessed();
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
