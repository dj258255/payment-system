/**
 * 원장(ledger) 모듈.
 *
 * <p>복식부기(double-entry) 원장. 결제/취소/정산 이벤트를 구독해 분개(journal entry)를
 * append-only로 기록하고, 차변 합계 = 대변 합계 불변식을 검증한다. 잔액은 파생값이다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.pay.ledger;
