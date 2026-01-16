package com.beomsu.pay.settlement;

/**
 * 정산 상태.
 *
 * <p>{@link #CREATED}(배치가 집계해 생성) → {@link #PAID_OUT}(어드민이 지급 확정)로 전이한다.
 * VARCHAR(20) 문자열 enum이라 값 추가에 DB 제약 변경이 필요 없다.
 */
public enum SettlementStatus {

    /** 배치가 집계해 생성한 초기 상태 */
    CREATED,

    /** 어드민이 가맹점 지급을 확정한 상태 */
    PAID_OUT
}
