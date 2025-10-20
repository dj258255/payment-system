package com.beomsu.pay.reconciliation;

/**
 * 대사 결과의 후속 처리 상태.
 *
 * <p>일치 건은 자동 종결, 나머지(불일치 3분류)는 사람 확인이 필요한 예외 큐로 남긴다.
 */
public enum ReconStatus {

    /** 자동 종결 — MATCHED */
    AUTO_RESOLVED,
    /** 사람 확인 필요 — INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH */
    PENDING
}
