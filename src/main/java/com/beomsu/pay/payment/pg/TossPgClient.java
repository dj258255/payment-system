package com.beomsu.pay.payment.pg;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 실 토스페이먼츠 어댑터.
 *
 * <p>{@link PgClient} 인터페이스만 구현하면 되므로, 기존 시스템에 <b>구현 하나만 추가</b>해 실 PG로
 * 갈아끼운다({@code @Qualifier("pgDelegate")} + {@code @Profile("prod")}). 승인/취소/조회를 토스 API로
 * 호출하고, 응답을 우리 도메인 타입으로 매핑한다. 5xx·네트워크 예외는 던져서 {@link ResilientPgClient}가
 * UNKNOWN(TIMEOUT)으로 변환하게 하고(실패로 단정하지 않음), 4xx 카드 거절만 명시적 FAILED로 매핑한다.
 */
@Component
@Qualifier("pgDelegate")
@Profile("prod")
public class TossPgClient implements PgClient {

    private final RestClient restClient;

    public TossPgClient(
            @Value("${payment.toss.base-url:https://api.tosspayments.com}") String baseUrl,
            @Value("${payment.toss.secret-key:}") String secretKey) {
        // 토스 인증: Basic base64(secretKey + ":")
        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        try {
            TossPayment resp = restClient.post()
                    .uri("/v1/payments/confirm")
                    .header("Idempotency-Key", command.orderNo())   // 주문번호로 PG 멱등 보장
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentKey", command.paymentKey(),
                            "orderId", command.orderNo(),
                            "amount", command.amount()))
                    .retrieve()
                    .body(TossPayment.class);
            return mapConfirm(resp);
        } catch (HttpClientErrorException e) {
            // 4xx — 카드 거절·한도 초과 등 명시적 실패(재시도 무의미)
            return PgApproveResult.failed("PG 거절: " + e.getStatusCode());
        }
        // 5xx·네트워크 예외는 여기서 잡지 않는다 → ResilientPgClient가 UNKNOWN으로 변환
    }

    @Override
    public PgCancelResult cancel(String paymentKey, long cancelAmount, String reason) {
        restClient.post()
                .uri("/v1/payments/{key}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", reason, "cancelAmount", cancelAmount))
                .retrieve()
                .body(TossPayment.class);
        return new PgCancelResult("toss-" + paymentKey);
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        try {
            TossPayment resp = restClient.get()
                    .uri("/v1/payments/{key}", paymentKey)
                    .retrieve()
                    .body(TossPayment.class);
            return new PgQueryResult(mapStatus(resp.status()), resp.method());
        } catch (HttpClientErrorException.NotFound e) {
            return new PgQueryResult(PgPaymentStatus.NOT_FOUND, null);   // PG에 결제 없음
        }
    }

    // --- 응답 매핑 (HTTP 없이 단위 테스트 가능하도록 static) ---

    static PgApproveResult mapConfirm(TossPayment resp) {
        if (resp != null && "DONE".equals(resp.status())) {
            return PgApproveResult.success(resp.method());
        }
        return PgApproveResult.failed("승인되지 않음: " + (resp == null ? "null" : resp.status()));
    }

    static PgPaymentStatus mapStatus(String tossStatus) {
        if (tossStatus == null) {
            return PgPaymentStatus.NOT_FOUND;
        }
        return switch (tossStatus) {
            case "DONE" -> PgPaymentStatus.APPROVED;
            case "CANCELED", "PARTIAL_CANCELED" -> PgPaymentStatus.CANCELED;
            default -> PgPaymentStatus.NOT_FOUND;   // READY/IN_PROGRESS/ABORTED/EXPIRED 등
        };
    }

    /** 토스 Payment 응답의 최소 형태. */
    record TossPayment(String status, String method) {
    }
}
