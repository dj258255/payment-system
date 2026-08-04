package com.beomsu.paysettlement;

/**
 * 정산 도메인 예외. code는 pay의 10-API-스펙 에러 코드 체계와 일치한다.
 *
 * <p>모놀리스에서는 shared 모듈의 {@code DomainException}을 상속했지만, 분리된 서비스는
 * pay 내부에 의존하지 않으므로 독립 예외로 둔다(코드 체계 계약만 공유).
 */
public class SettlementException extends RuntimeException {

    private final String code;

    public SettlementException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 지급 확정 대상 정산을 찾지 못한 경우 — 404. */
    static SettlementException notFound(long settlementId) {
        return new SettlementException("SETTLEMENT_NOT_FOUND",
                "정산을 찾을 수 없습니다: " + settlementId);
    }
}
