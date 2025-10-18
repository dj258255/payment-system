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
        this.status = SettlementItemStatus.PENDING;
    }

    /** 결제 승인 항목을 PENDING 상태로 만든다. */
    public static SettlementItem of(long paymentId, String orderNo, long amount, LocalDate confirmedDate) {
        return new SettlementItem(paymentId, orderNo, amount, confirmedDate);
    }

    /** 배치가 이 항목을 특정 날짜 정산에 집계 완료로 표시한다. */
    public void markSettled() {
        this.status = SettlementItemStatus.SETTLED;
    }
}
