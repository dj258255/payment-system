/**
 * 주문(order) 모듈.
 *
 * <p>주문 생성, 주문 상태머신(CREATED → PENDING_PAYMENT → PAID → ...), 금액 위변조 검증의
 * 기준값이 되는 total_amount를 관리한다. 결제 결과는 payment 모듈이 발행하는 이벤트를
 * 구독해 반영하며, payment 모듈을 직접 호출하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.pay.order;
