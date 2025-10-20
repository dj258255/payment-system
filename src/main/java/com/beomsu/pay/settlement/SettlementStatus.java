package com.beomsu.pay.settlement;

/**
 * 정산 상태.
 *
 * <p>Phase 4는 집계까지만 다루므로 {@link #CREATED}만 사용한다. 실무에선
 * CREATED → CONFIRMED(검수) → PAID(지급 완료)로 확장한다.
 */
public enum SettlementStatus {

    /** 배치가 집계해 생성한 초기 상태 */
    CREATED
}
