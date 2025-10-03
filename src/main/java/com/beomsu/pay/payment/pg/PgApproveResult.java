package com.beomsu.pay.payment.pg;

/**
 * PG 승인 결과.
 * @param outcome   3-상태 결과
 * @param method    결제수단 (SUCCESS일 때만)
 * @param failReason 실패/타임아웃 사유
 */
public record PgApproveResult(PgOutcome outcome, String method, String failReason) {

    public static PgApproveResult success(String method) {
        return new PgApproveResult(PgOutcome.SUCCESS, method, null);
    }

    public static PgApproveResult failed(String reason) {
        return new PgApproveResult(PgOutcome.FAILED, null, reason);
    }

    public static PgApproveResult timeout(String reason) {
        return new PgApproveResult(PgOutcome.TIMEOUT, null, reason);
    }
}
