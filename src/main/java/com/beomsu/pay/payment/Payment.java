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

    // --- 복구(recovery) 경로: UNKNOWN 결제를 배치가 조회 후 확정한다 ---

    /** UNKNOWN → DONE. PG 조회 결과 실제로는 승인돼 있었을 때(복구 전진). */
    public void confirmByRecovery(String method) {
        transitionTo(PaymentStatus.DONE, TriggeredBy.RECOVERY_BATCH, "복구: PG 승인 확인");
        this.method = method;
        this.approvedAt = Instant.now();
    }

    /** UNKNOWN → ABORTED. PG에 결제 정보가 없을 때(승인이 실제로 안 됨). */
    public void abortByRecovery(String reason) {
        transitionTo(PaymentStatus.ABORTED, TriggeredBy.RECOVERY_BATCH, reason);
    }

    /**
     * UNKNOWN → CANCELED. 망취소(network cancel).
     * 타임아웃 뒤 이 결제를 진행하지 않기로 했을 때, PG에 남은 "유령 승인"을 정리한다.
     */
    public void networkCancel(String reason) {
        transitionTo(PaymentStatus.CANCELED, TriggeredBy.RECOVERY_BATCH, reason);
        this.balanceAmount = 0;
    }

    /**
     * 취소(전액/부분). 잔액을 초과하면 예외. 잔액이 0이 되면 CANCELED, 남으면 PARTIAL_CANCELED.
     */
    public void cancel(Money cancelAmount, TriggeredBy by, String reason) {
        validateCancelable(cancelAmount);
        long newBalance = balanceAmount - cancelAmount.amount();
        transitionTo(cancelTargetOf(newBalance), by, reason);
        this.balanceAmount = newBalance;
    }

    /**
     * 취소 가능 여부만 검사한다(상태 변경 없음). 취소 사가 1단계가 <b>PG를 부르기 전에</b> 불가능한
     * 취소를 걸러내는 데 쓴다. {@link #cancel}도 같은 검사를 통과한 뒤 전이하므로 규칙이 한 곳에 있다.
     */
    public void validateCancelable(Money cancelAmount) {
        if (cancelAmount.amount() > balanceAmount) {
            throw PaymentException.cancelAmountExceeded(cancelAmount.amount(), balanceAmount);
        }
        PaymentStatus target = cancelTargetOf(balanceAmount - cancelAmount.amount());
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(status, target);
        }
    }

    /** 취소 후 잔액이 0이면 전액 취소, 남으면 부분 취소. */
    private static PaymentStatus cancelTargetOf(long newBalance) {
        return (newBalance == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
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
