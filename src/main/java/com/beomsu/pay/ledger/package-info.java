/**
 * 원장(ledger) 모듈 — 복식부기(double-entry).
 *
 * <p>payment의 결제 완료·취소 이벤트를 구독해 분개(journal entry)를 append-only로 기록한다.
 * 모든 거래는 차변 합계 = 대변 합계를 만족해야 하며, 이 불변식이 자금 이동의 정합성을
 * 수학적으로 보장한다. 잔액은 엔트리의 합으로 파생된다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment" }
)
package com.beomsu.pay.ledger;
