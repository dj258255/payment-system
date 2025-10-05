package com.beomsu.pay.order.web;

import com.beomsu.pay.order.CheckoutResult;
import com.beomsu.pay.order.CheckoutService;
import com.beomsu.pay.shared.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 승인(체크아웃) REST 컨트롤러.
 *
 * <p>프론트가 successUrl로 받은 파라미터를 그대로 전달하면, 서버가 금액 검증 후 PG 승인을 호출한다.
 * 응답 상태코드로 승인 결과를 구분한다: 승인 완료 200 / 미확정(UNKNOWN) 202 / 거절 400 (10-API-스펙).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/confirm")
    public ResponseEntity<CheckoutResult> confirm(@RequestBody ConfirmRequest request) {
        CheckoutResult result = checkoutService.confirm(
                request.orderNo(), request.paymentKey(), Money.of(request.amount()));

        HttpStatus status = switch (result.paymentStatus()) {
            case DONE -> HttpStatus.OK;               // 승인 완료
            case UNKNOWN -> HttpStatus.ACCEPTED;      // 미확정 → 폴링 안내
            default -> HttpStatus.BAD_REQUEST;        // 거절(ABORTED) 등
        };
        return ResponseEntity.status(status).body(result);
    }

    /** 결제 승인 요청 (Idempotency-Key는 Phase 2에서 헤더로 추가). */
    public record ConfirmRequest(String paymentKey, String orderNo, long amount) {
    }
}
