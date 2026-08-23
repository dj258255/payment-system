package com.beomsu.pay.payment.pg;

/**
 * PG 승인 결과.
 *
 * <p>{@code provider}가 있는 이유는 취소 때문이다. 이중화를 켜면 승인이 다른 PG로 넘어갈 수
 * 있는데, 나중에 취소할 때 어디로 보내야 하는지 알아야 한다. 다른 PG에 보내면 "그런 거래 없음"이
 * 돌아오고 그것이 취소 결과가 된다 — 고객 돈은 원 PG에 잡혀 있는데 우리는 취소했다고 적는다.
 *
 * @param outcome    3-상태 결과
 * @param method     결제수단 (SUCCESS일 때만)
 * @param failReason 실패/타임아웃 사유
 * @param provider   실제로 승인을 처리한 PG 이름. 단일 PG면 null일 수 있다
 */
public record PgApproveResult(PgOutcome outcome, String method, String failReason, String provider) {

    public static PgApproveResult success(String method) {
        return new PgApproveResult(PgOutcome.SUCCESS, method, null, null);
    }

    public static PgApproveResult success(String method, String provider) {
        return new PgApproveResult(PgOutcome.SUCCESS, method, null, provider);
    }

    public static PgApproveResult failed(String reason) {
        return new PgApproveResult(PgOutcome.FAILED, null, reason, null);
    }

    public static PgApproveResult timeout(String reason) {
        return new PgApproveResult(PgOutcome.TIMEOUT, null, reason, null);
    }

    /** 어느 PG가 처리했는지 알게 된 결과로 바꾼다. 라우터가 자기 이름을 붙일 때 쓴다 */
    public PgApproveResult withProvider(String provider) {
        return new PgApproveResult(outcome, method, failReason, provider);
    }
}
