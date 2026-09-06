package com.beomsu.pay.settlement.internal;

/**
 * 정산 상태.
 *
 * <p>{@link #CREATED}(배치가 집계해 생성) → {@link #PAID_OUT}(어드민이 지급 확정)로 전이한다.
 * VARCHAR(20) 문자열 enum이라 값 추가에 DB 제약 변경이 필요 없다.
 *
 * <p><b>{@link #PAYOUT_HELD} 를 따로 둔 이유</b>: 제재 스크리닝에 걸린 판매자의 정산은
 * <b>집계는 하되 돈은 안 나가야</b> 한다. 집계까지 건너뛰면 나중에 심사가 풀렸을 때
 * 그 날짜 매출이 영영 안 잡힌다 — 이 집계 키는 이미 한 번 조용히 틀려 지급이 통째로 빠졌던
 * 자리다. 그래서 만들되 표시한다.
 */
public enum SettlementStatus {

    /** 배치가 집계해 생성한 초기 상태 */
    CREATED,

    /**
     * 판매자 심사에 걸려 <b>지급을 막은</b> 상태. 집계는 끝났고 금액도 맞다.
     * 심사가 풀리면 사람이 {@link #CREATED} 로 되돌리고 그때 지급한다.
     */
    PAYOUT_HELD,

    /** 어드민이 가맹점 지급을 확정한 상태 */
    PAID_OUT;

    /** 지급 확정을 걸 수 있는 상태인가. <b>보류는 못 건다.</b> */
    public boolean payable() {
        return this == CREATED;
    }
}
