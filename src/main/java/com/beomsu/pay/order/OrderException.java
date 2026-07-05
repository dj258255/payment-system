package com.beomsu.pay.order;

import com.beomsu.pay.shared.DomainException;

/** 주문 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. */
public class OrderException extends DomainException {

    public OrderException(String code, String message) {
        super(code, message);
    }

    /** 금액 위변조 검증 실패 — order가 total_amount의 소유자이므로 이 검증도 order가 담당한다. */
    public static OrderException amountMismatch(long expected, long requested) {
        return new OrderException("AMOUNT_MISMATCH",
                "결제 요청 금액이 주문 금액과 일치하지 않습니다: 주문 %d, 요청 %d".formatted(expected, requested));
    }

    public static OrderException orderNotFound(String orderNo) {
        return new OrderException("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다: " + orderNo);
    }

    public static OrderException productNotFound(long productId) {
        return new OrderException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + productId);
    }

    public static OrderException outOfStock(long productId) {
        return new OrderException("OUT_OF_STOCK", "재고가 부족합니다: 상품 " + productId);
    }

    public static OrderException invalidTransition(OrderStatus from, OrderStatus to) {
        return new OrderException("INVALID_STATE_TRANSITION",
                "허용되지 않은 상태 전이입니다: %s → %s".formatted(from, to));
    }
}
