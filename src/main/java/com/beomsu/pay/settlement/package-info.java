/**
 * 정산(settlement) 모듈.
 *
 * <p>Spring Batch 기반 일 단위 거래 집계 → 수수료 계산 → 가맹점 지급금 생성.
 * 배치 재실행 멱등성((merchant_id, settlement_date) 유니크)을 보장한다.
 *
 * <p><b>에스크로 정렬</b>: 정산은 payment(승인·취소)뿐 아니라 escrow의 {@code EscrowReleasedEvent}
 * (구매확정)를 구독해 정산 가능 시점을 에스크로 릴리스에 맞춘다. 의존은 settlement→escrow 단방향이며
 * (escrow는 settlement를 모른다), escrow가 {shared, payment}만 의존하므로 순환이 없다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "escrow" }
)
package com.beomsu.pay.settlement;
