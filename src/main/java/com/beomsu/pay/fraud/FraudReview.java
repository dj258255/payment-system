package com.beomsu.pay.fraud;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * FDS 심사 큐 항목 애그리거트 — 사후 탐지가 REVIEW/BLOCK으로 판정한 결제를 사람 검토 대상으로 담는다.
 *
 * <p>결제 완료 이벤트를 받은 {@link FraudPostHocListener}가 판정 엔진을 재평가해 REVIEW/BLOCK이면
 * {@link #flagged}로 PENDING 항목을 만든다. 어드민이 {@link #approve}(정상 확인)/{@link #reject}
 * (부정 확인 → 카드 블랙리스트)로 종결한다. 상태 전이는 PENDING에서만 출발하도록 가드해 이미
 * 종결된 항목의 재처리를 막는다({@code Order}·{@code Payment}·{@code EscrowHold}와 동일한 가드 방식).
 */
@Entity
@Table(name = "fraud_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudReview {

    /** reasons 컬럼 최대 길이 — 룰 근거를 콤마로 이어 담되 이 길이를 넘지 않게 방어 절단한다. */
    private static final int REASONS_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String orderNo;

    @Column(nullable = false)
    private long paymentId;

    /** PG 결제 키(=카드 식별자). 거부 시 이 키를 블랙리스트에 등록한다. */
    @Column(nullable = false, length = 200)
    private String cardKey;

    @Column(nullable = false)
    private long amount;

    /** 사후 재평가 점수. */
    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FdsDecision decision;

    /** 발동한 룰 근거(콤마 join, 최대 500자). */
    @Column(length = 500)
    private String reasons;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudReviewStatus status;

    /** 승인/거부한 어드민(principal명). 미해결이면 null. */
    @Column(length = 100)
    private String reviewedBy;

    @Column(nullable = false)
    private Instant createdAt;

    /** 승인/거부로 종결된 시각. 미해결이면 null. */
    private Instant reviewedAt;

    private FraudReview(String orderNo, long paymentId, String cardKey, long amount,
                        int score, FdsDecision decision, String reasons) {
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.cardKey = cardKey;
        this.amount = amount;
        this.score = score;
        this.decision = decision;
        this.reasons = reasons;
        this.status = FraudReviewStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * 사후 탐지 결과를 PENDING 심사 항목으로 적재한다. 점수·판정·근거는 재평가 결과({@link FraudResult})
     * 에서 가져오고, 근거는 콤마로 이어 500자로 방어 절단한다.
     */
    public static FraudReview flagged(String orderNo, long paymentId, String cardKey, long amount,
                                      FraudResult result) {
        String reasons = String.join(",", result.reasons());
        if (reasons.length() > REASONS_MAX) {
            reasons = reasons.substring(0, REASONS_MAX);
        }
        return new FraudReview(orderNo, paymentId, cardKey, amount,
                result.score(), result.decision(), reasons);
    }

    /**
     * 승인 — PENDING → APPROVED(정상 거래로 확인). 이미 종결된 항목이면 예외를 던진다.
     */
    public void approve(String reviewer) {
        requirePending();
        this.status = FraudReviewStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    /**
     * 거부 — PENDING → REJECTED(부정 거래로 확인). 카드 블랙리스트 등록은 서비스가 수행한다.
     * 이미 종결된 항목이면 예외를 던진다.
     */
    public void reject(String reviewer) {
        requirePending();
        this.status = FraudReviewStatus.REJECTED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    private void requirePending() {
        if (this.status != FraudReviewStatus.PENDING) {
            throw FraudException.invalidState(
                    "PENDING 상태의 심사만 처리할 수 있습니다: id=%s, status=%s".formatted(id, status));
        }
    }
}
