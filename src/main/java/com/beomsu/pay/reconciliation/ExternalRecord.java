package com.beomsu.pay.reconciliation;

/**
 * PG 정산 파일의 한 줄 — 외부 기록(불변 입력 DTO).
 *
 * <p>엔티티가 아니라 매칭 엔진의 입력이다. 파일 적재 계층에서 파싱해 넘긴다.
 */
public record ExternalRecord(String orderNo, long amount) {
}
