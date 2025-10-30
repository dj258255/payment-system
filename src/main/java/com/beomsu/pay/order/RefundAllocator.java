package com.beomsu.pay.order;

/**
 * 부분취소 환불 배분기 — 취소 금액을 결제 수단(포인트/카드)에 배분한다.
 *
 * <p><b>포인트를 먼저 환불한다.</b> 카드부터 환불하면 무상으로 지급된 포인트를 현금화하는 어뷰징이
 * 가능하기 때문이다(예: 10,000원 = 포인트 3,000 + 카드 7,000 결제 후 7,000원을 카드로 환불받으면
 * 포인트 3,000이 사실상 현금이 된다). 포인트 복원은 내부라 즉시·확실하고, 카드 부분취소는 카드사
 * 의존이며 회계상 역분개도 최소화된다.
 */
public final class RefundAllocator {

    private RefundAllocator() {
    }

    /**
     * 취소 금액을 포인트 우선으로 배분한다.
     * 불변식: {@code fromPoint + fromCard == cancelAmount}, 각 배분액은 해당 결제분을 넘지 않는다.
     *
     * @param cancelAmount 이번에 취소할 금액
     * @param paidByPoint  이 주문에서 포인트로 결제한(아직 환불되지 않은) 금액
     * @param paidByCard   이 주문에서 카드로 결제한(아직 환불되지 않은) 금액
     */
    public static RefundAllocation allocate(long cancelAmount, long paidByPoint, long paidByCard) {
        if (cancelAmount < 0 || paidByPoint < 0 || paidByCard < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다.");
        }
        if (cancelAmount > paidByPoint + paidByCard) {
            throw new IllegalArgumentException(
                    "취소 금액이 잔여 결제 금액을 초과합니다: 취소 %d, 잔여 %d"
                            .formatted(cancelAmount, paidByPoint + paidByCard));
        }
        long fromPoint = Math.min(cancelAmount, paidByPoint); // 포인트 먼저
        long fromCard = cancelAmount - fromPoint;             // 나머지를 카드에서
        return new RefundAllocation(fromPoint, fromCard);
    }

    /** 환불 배분 결과. */
    public record RefundAllocation(long fromPoint, long fromCard) {
    }
}
