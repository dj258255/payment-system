package com.beomsu.pay.reconciliation;

/**
 * 대사 1회 실행 요약 — 업로드한 PG 정산 파일로 매칭 엔진을 돌린 결과 집계.
 *
 * @param external       파싱된 외부 기록 수(엔진에 투입된 행 수)
 * @param skipped        파싱 중 건너뛴 불량/요약 행 수
 * @param matched        일치(자동 종결)
 * @param internalOnly   내부에만 있음(PG 누락 의심)
 * @param externalOnly   외부에만 있음(내부 유실 의심)
 * @param amountMismatch 금액 불일치
 * @param pending        사람 확인이 필요한 건(= internalOnly + externalOnly + amountMismatch), 예외 큐로 남는 수
 */
public record ReconRunSummary(
        int external,
        int skipped,
        int matched,
        int internalOnly,
        int externalOnly,
        int amountMismatch,
        int pending) {
}
