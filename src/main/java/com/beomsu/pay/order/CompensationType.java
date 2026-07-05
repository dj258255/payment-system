package com.beomsu.pay.order;

/** 보상 태스크 유형. 현재는 카드 승인 후 재고 부족 시의 망취소(NETWORK_CANCEL) 한 가지. */
public enum CompensationType {
    /** 승인된 카드를 취소하는 망취소 — 외부 PG 호출이라 불확실해 durable 재시도로 처리한다. */
    NETWORK_CANCEL
}
