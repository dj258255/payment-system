package com.beomsu.pay.ledger;

/** 원장 계정 유형(시스템 계정). 결제는 PG 미수금↔매출 사이의 자금 이동으로 기록한다. */
public enum AccountType {
    PG_RECEIVABLE,  // PG로부터 받을 돈(자산)
    SALES           // 매출(수익)
}
