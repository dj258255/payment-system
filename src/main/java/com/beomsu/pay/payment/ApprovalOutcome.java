package com.beomsu.pay.payment;

/**
 * PG 승인 결과의 <b>모듈 노출용</b> 표현. 체크아웃 사가(ADR-007)에서 orchestrator(order 모듈)가
 * PG 단계(트랜잭션 밖)와 확정 단계(트랜잭션) 사이를 이 값으로 건넨다.
 *
 * <p>내부 PG 어댑터 타입({@code payment.pg.PgApproveResult})은 payment 모듈 내부라 order가 참조하면
 * 모듈 경계를 위반한다. 그래서 payment 루트에 이 노출용 DTO를 두고, {@link PaymentService#pgApprove}가
 * 매핑해 돌려준다 — order는 이 값을 <b>불투명하게</b> 들고 있다가 {@link PaymentService#applyResult}에 넘긴다.
 *
 * @param result     승인 결과 3-상태(SUCCESS/FAILED/TIMEOUT)
 * @param method     승인 수단(성공 시), 그 외 null
 * @param failReason 실패/미확정 사유, 성공 시 null
 */
public record ApprovalOutcome(Result result, String method, String failReason) {

    public enum Result { SUCCESS, FAILED, TIMEOUT }
}
