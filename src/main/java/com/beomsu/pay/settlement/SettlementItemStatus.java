package com.beomsu.pay.settlement;

/**
 * 정산 대상 항목의 상태.
 *
 * <p>{@link #PENDING} 은 결제 승인으로 적재됐으나 아직 일 단위 정산에 묶이지 않은 상태,
 * {@link #SETTLED} 는 특정 날짜 정산({@link Settlement})에 집계 완료된 상태.
 */
public enum SettlementItemStatus {

    /** 정산 대기 — 결제 승인 이벤트로 적재됐고, 아직 배치에 집계되지 않음 */
    PENDING,
    /** 정산 완료 — 해당 날짜 정산에 집계됨 */
    SETTLED
}
