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

    /** 주문 생성 요청. 클라이언트는 productId·quantity만 보내고, 가격은 서버가 카탈로그에서 조회한다. */
    public record CreateOrderRequest(long userId, List<OrderLine> items) {
    }
}
