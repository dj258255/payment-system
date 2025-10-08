package com.beomsu.pay.order.web;

import com.beomsu.pay.order.CheckoutResult;
import com.beomsu.pay.order.CheckoutService;
import com.beomsu.pay.order.idempotency.IdempotencyService;
import com.beomsu.pay.shared.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 승인(체크아웃) REST 컨트롤러.
 *
 * <p>프론트가 successUrl로 받은 파라미터를 그대로 전달하면, 서버가 금액 검증 후 PG 승인을 호출한다.
 * 응답 상태코드로 승인 결과를 구분한다: 승인 완료 200 / 미확정(UNKNOWN) 202 / 거절 400 (10-API-스펙).
 *
 * <p>모든 승인 요청은 {@code Idempotency-Key} 헤더로 멱등 처리된다 — "따닥" 중복결제와
 * 타임아웃 후 재시도를 안전하게 만든다.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class CheckoutController {

    private static final String PATH = "/api/v1/payments/confirm";

    private final CheckoutService checkoutService;
    private final IdempotencyService idempotencyService;

    public CheckoutController(CheckoutService checkoutService, IdempotencyService idempotencyService) {
        this.checkoutService = checkoutService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/confirm")
    public ResponseEntity<CheckoutResult> confirm(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfirmRequest request) {

        CheckoutResult result = idempotencyService.execute(
                idempotencyKey, PATH, "POST", request, CheckoutResult.class,
                () -> checkoutService.confirm(
                        request.orderNo(), request.paymentKey(), Money.of(request.amount())));

        HttpStatus status = switch (result.paymentStatus()) {
            case DONE -> HttpStatus.OK;               // 승인 완료
            case UNKNOWN -> HttpStatus.ACCEPTED;      // 미확정 → 폴링 안내
            default -> HttpStatus.BAD_REQUEST;        // 거절(ABORTED) 등
        };
        return ResponseEntity.status(status).body(result);
    }

    public record ConfirmRequest(String paymentKey, String orderNo, long amount) {
    }
}
