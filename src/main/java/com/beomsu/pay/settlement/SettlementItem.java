package com.beomsu.pay.settlement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 정산 대상 항목 — 결제 승인 1건이 곧 정산의 최소 단위다.
 *
 * <p>{@code paymentId} 유니크로 같은 결제가 두 번 적재되는 것을 DB가 차단한다 — 적재 멱등성.
 * 결제 승인 시각(UTC)을 {@code confirmedDate}로 스냅샷해, 일 단위 배치가 이 날짜로 집계한다.
 */
@Entity
@Table(name = "settlement_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_item_payment", columnNames = {"paymentId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long paymentId;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    /** 결제 승인 시각을 UTC 기준 날짜로 스냅샷한 값 — 일 단위 집계의 기준일 */
    @Column(nullable = false)
    private LocalDate confirmedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemStatus status;

    private SettlementItem(long paymentId, String orderNo, long amount, LocalDate confirmedDate) {
        this.paymentId = paymentId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.confirmedDate = confirmedDate;
        this.status = SettlementItemStatus.PENDING_CONFIRMATION;
    }

    /** 결제 승인 항목을 PENDING_CONFIRMATION(구매확정 대기) 상태로 만든다. */
    public static SettlementItem of(long paymentId, String orderNo, long amount, LocalDate confirmedDate) {
        return new SettlementItem(paymentId, orderNo, amount, confirmedDate);
    }

    /**
     * 에스크로 릴리스(구매확정) → 정산 가능. PENDING_CONFIRMATION일 때만 CONFIRMED로 전이한다.
     *
     * <p>멱등: 이미 CONFIRMED/SETTLED/CANCELED면 무시한다(이벤트 중복 전달·순서 역전 대비).
     */
    public void confirm() {
        if (this.status == SettlementItemStatus.PENDING_CONFIRMATION) {
            this.status = SettlementItemStatus.CONFIRMED;
        }
    }

    /**
     * 배치가 이 항목을 특정 날짜 정산에 집계 완료로 표시한다. CONFIRMED일 때만 SETTLED로 전이한다.
     * (배치는 CONFIRMED만 조회하므로 방어적 가드지만, 멱등·상태 불변식을 엔티티에 고정한다.)
     */
    public void markSettled() {
        if (this.status == SettlementItemStatus.CONFIRMED) {
            this.status = SettlementItemStatus.SETTLED;
        }
    }

    /**
     * 구매확정 전 전액취소 → 정산 제외. PENDING_CONFIRMATION/CONFIRMED일 때만 CANCELED로 전이한다.
     *
     * <p>멱등: 이미 SETTLED(집계 완료)면 차감할 수 없어 무시하고, 이미 CANCELED면 무시한다.
     */
    public void cancel() {
        if (this.status == SettlementItemStatus.PENDING_CONFIRMATION
                || this.status == SettlementItemStatus.CONFIRMED) {
            this.status = SettlementItemStatus.CANCELED;
        }
    }

    /**
     * 부분취소 반영 — 정산 대상 금액을 취소분만큼 줄인다(0 미만으로는 내려가지 않는다).
     *
     * <p>상태는 유지한다(부분취소 후에도 잔액은 여전히 정산 대상). SETTLED 항목엔 호출하지 않으며
     * (서비스에서 분기), at-least-once 중복 배달 시 이중 차감 위험은 서비스 주석에 한계로 명시한다.
     */
    public void reduce(long cancelAmount) {
        this.amount = Math.max(0L, this.amount - cancelAmount);
    }
}
