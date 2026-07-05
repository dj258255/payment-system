package com.beomsu.pay.payment;

import com.beomsu.pay.shared.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 결제 애그리거트.
 *
 * <p>상태 전이는 {@link #transitionTo}를 통해서만 일어나며, {@link PaymentStatus}의 허용 전이표를
 * 위반하면 예외가 난다. 모든 전이는 {@link PaymentHistory}에 append-only로 기록된다.
 * 낙관적 락({@code @Version})으로 동시 전이를 감지한다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(length = 200)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    /** 취소 가능 잔액. 부분취소마다 차감된다. */
    @Column(nullable = false)
    private long balanceAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(length = 30)
    private String method;

    @Column(nullable = false, length = 30)
    private String pgProvider = "TOSS_PAYMENTS";

    /** UNKNOWN 진입 사유 (타임아웃/5xx 등) */
    @Column(length = 200)
    private String unknownReason;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant approvedAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private final List<PaymentHistory> histories = new ArrayList<>();

    private Payment(String orderNo, long amount) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.balanceAmount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = Instant.now();
    }

    /** 결제 시작 — READY 상태로 생성한다. */
    public static Payment initiate(String orderNo, Money amount) {
        return new Payment(orderNo, amount.amount());
    }

    /** READY → IN_PROGRESS. paymentKey를 귀속시키고 승인을 시작한다. */
    public void startApproval(String paymentKey) {
        this.paymentKey = paymentKey;
        transitionTo(PaymentStatus.IN_PROGRESS, TriggeredBy.USER, "승인 요청");
    }

    /** IN_PROGRESS → DONE. */
    public void approve(String method) {
        transitionTo(PaymentStatus.DONE, TriggeredBy.USER, "승인 완료");
        this.method = method;
        this.approvedAt = Instant.now();
    }

    /** IN_PROGRESS → UNKNOWN. 타임아웃 등 미확정 상태. 복구 배치/망취소의 대상이 된다. */
    public void markUnknown(String reason) {
        this.unknownReason = reason;
        transitionTo(PaymentStatus.UNKNOWN, TriggeredBy.USER, reason);
    }

    /** → ABORTED. 승인 실패. */
    public void abort(String reason) {
        transitionTo(PaymentStatus.ABORTED, TriggeredBy.USER, reason);
    }

    /**
     * 취소(전액/부분). 잔액을 초과하면 예외. 잔액이 0이 되면 CANCELED, 남으면 PARTIAL_CANCELED.
     */
    public void cancel(Money cancelAmount, TriggeredBy by, String reason) {
        if (cancelAmount.amount() > balanceAmount) {
            throw PaymentException.cancelAmountExceeded(cancelAmount.amount(), balanceAmount);
        }
        long newBalance = balanceAmount - cancelAmount.amount();
        PaymentStatus target = (newBalance == 0)
                ? PaymentStatus.CANCELED
                : PaymentStatus.PARTIAL_CANCELED;
        transitionTo(target, by, reason);
        this.balanceAmount = newBalance;
    }

    private void transitionTo(PaymentStatus target, TriggeredBy by, String reason) {
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(status, target);
        }
        histories.add(PaymentHistory.of(this, status, target, by, reason));
        this.status = target;
    }

    public Money amountAsMoney() {
        return Money.of(amount);
    }

    public Money balanceAsMoney() {
        return Money.of(balanceAmount);
    }

    public List<PaymentHistory> getHistories() {
        return List.copyOf(histories);
    }
}
