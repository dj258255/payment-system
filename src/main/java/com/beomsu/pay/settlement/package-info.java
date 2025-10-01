/**
 * 정산(settlement) 모듈.
 *
 * <p>Spring Batch 기반 일 단위 거래 집계 → 수수료 계산 → 가맹점 지급금 생성.
 * 배치 재실행 멱등성((merchant_id, settlement_date) 유니크)을 보장한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.pay.settlement;
