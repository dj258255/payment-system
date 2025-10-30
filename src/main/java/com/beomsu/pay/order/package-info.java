/**
 * 주문(order) 모듈.
 *
 * <p>주문 생성, 주문 상태머신(CREATED → PENDING_PAYMENT → PAID → ...), 금액 위변조 검증의
 * 기준값이 되는 total_amount를 관리한다. 결제 결과는 payment 모듈이 발행하는 이벤트를
 * 구독해 반영하며, payment 모듈을 직접 호출하지 않는다.
 *
 * <p>복합결제(포인트+카드) 오케스트레이션을 위해 point 모듈에 의존한다 — 포인트 선점/복원은
 * order의 체크아웃 흐름이 조율하는 내부 Saga의 한 단계다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "point" }
)
package com.beomsu.pay.order;
