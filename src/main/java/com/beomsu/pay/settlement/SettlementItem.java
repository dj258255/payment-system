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

    /**
     * 정산 집계 기준일. 적재({@link #of}) 시엔 승인일을 placeholder로 담지만(PENDING은 집계 대상이 아니라
     * 이 값이 쓰이지 않는다), <b>구매확정(에스크로 릴리스) 시 {@link #confirm}이 릴리스 날짜로 재스탬프</b>한다.
     *
     * <p>재스탬프가 핵심이다 — 배치는 "그 날짜에 CONFIRMED된 항목"을 집계하는데, 에스크로 홀드는 며칠에
     * 걸쳐 있어 승인일과 확정일이 다르다. 승인일로 그대로 두면 확정된 항목이 승인일 배치(이미 지나감)에도,
     * 확정일 배치(그 날짜엔 confirmedDate가 안 맞음)에도 안 잡혀 <b>영구 미정산</b>이 된다. 확정일로
     * 재스탬프해야 확정 다음 날 배치가 정확히 집계한다.
     */
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
     * 에스크로 릴리스(구매확정) → 정산 가능. PENDING_CONFIRMATION일 때만 CONFIRMED로 전이하고,
     * {@code confirmedDate}를 <b>릴리스 날짜로 재스탬프</b>한다 — 이 날짜 기준으로 배치가 집계한다.
     *
     * <p>멱등: 이미 CONFIRMED/SETTLED/CANCELED면 상태·날짜 모두 그대로 둔다(이벤트 중복 전달·순서
     * 역전 대비). 재스탬프도 최초 전이 시 1회만 일어나 재배달에 안전하다.
     *
     * @param settlementReadyDate 구매확정(릴리스)이 일어난 날짜 — 정산 집계 기준일
     */
    public void confirm(LocalDate settlementReadyDate) {
        if (this.status == SettlementItemStatus.PENDING_CONFIRMATION) {
            this.status = SettlementItemStatus.CONFIRMED;
            this.confirmedDate = settlementReadyDate;
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
     * 부분취소 반영 — 정산 대상 금액을 <b>취소 후 잔액(절대값)으로 세팅</b>한다. 상태는 유지한다
     * (부분취소 후에도 잔액은 여전히 정산 대상).
     *
     * <p><b>멱등</b>: 델타를 빼는 게 아니라 절대 잔액으로 세팅하므로, 같은 취소 이벤트가 at-least-once로
     * 여러 번 배달돼도 같은 값이 되어 이중 차감되지 않는다. (같은 주문의 취소 이벤트는 orderNo 라우팅으로
     * 같은 파티션에서 순서 보존되므로, 더 과거 취소가 뒤늦게 재배달되는 역전은 실질적으로 배제된다.)
     * SETTLED 항목엔 호출하지 않는다(서비스에서 분기).
     */
    public void applySettleableBalance(long settleableBalance) {
        this.amount = Math.max(0L, settleableBalance);
    }
}
