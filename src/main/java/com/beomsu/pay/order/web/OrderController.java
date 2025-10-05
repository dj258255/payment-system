package com.beomsu.pay.order.web;

import com.beomsu.pay.order.CheckoutService;
import com.beomsu.pay.order.CreateOrderResult;
import com.beomsu.pay.order.OrderLine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 주문 생성 REST 컨트롤러. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CheckoutService checkoutService;

    public OrderController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public ResponseEntity<CreateOrderResult> create(@RequestBody CreateOrderRequest request) {
        CreateOrderResult result = checkoutService.createOrder(request.userId(), request.items());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** 주문 생성 요청. Phase 1은 상품 스냅샷(이름·단가)을 요청에서 직접 받는다. */
    public record CreateOrderRequest(long userId, List<OrderLine> items) {
    }
}
