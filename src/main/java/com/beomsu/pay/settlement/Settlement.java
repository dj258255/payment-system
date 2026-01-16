package com.beomsu.pay.settlement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일 단위 정산 — 하루치 거래를 집계한 가맹점 지급금 묶음.
 *
 * <p>불변식: <b>netAmount = grossAmount - feeAmount - feeVatAmount</b>. 생성 시점에 강제하며, 위반하면
 * 예외가 난다. {@code settlementDate} 유니크로 같은 날짜 정산이 두 번 만들어지는 것을 DB가 차단한다 —
 * 배치 재실행 멱등성의 핵심.
 *
 * <p>수수료는 수수료(feeAmount)와 그 부가세(feeVatAmount)로 나눠 잡는다(실무형). 지급예정일
 * ({@code payoutDate})은 정산일 + N영업일이며, 지급 확정 시각({@code paidOutAt})은 어드민이
 * {@link #markPaidOut()}으로 채운다.
 */
@Entity
@Table(name = "settlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_date", columnNames = {"settlementDate"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate settlementDate;

    /** 거래 총액 */
    @Column(nullable = false)
    private long grossAmount;

    /** 수수료 합 */
    @Column(nullable = false)
    private long feeAmount;

    /** 수수료 부가세(수수료의 10%) */
    @Column(nullable = false)
    private long feeVatAmount;

    /** 지급액 = gross - fee - feeVat (불변식 검증 대상) */
    @Column(nullable = false)
    private long netAmount;

    @Column(nullable = false)
    private int itemCount;

    /** 지급예정일 = settlementDate + N영업일(주말 skip). 신규 집계는 항상 계산해 채운다. */
    @Column(nullable = false)
    private LocalDate payoutDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** 지급 확정 시각(nullable) — 어드민이 지급을 확정한 순간. 미확정이면 null. */
    private Instant paidOutAt;

    private Settlement(LocalDate settlementDate, long grossAmount, long feeAmount, long feeVatAmount,
                       int itemCount, LocalDate payoutDate) {
        this.settlementDate = settlementDate;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.feeVatAmount = feeVatAmount;
        this.netAmount = grossAmount - feeAmount - feeVatAmount;
        this.itemCount = itemCount;
        this.payoutDate = payoutDate;
        this.status = SettlementStatus.CREATED;
        this.createdAt = Instant.now();
    }

    /**
     * 하루치 집계로 정산을 만든다. netAmount는 gross - fee - feeVat로 계산하며, 불변식
     * (net = gross - fee - feeVat)을 생성 직후 재검증한다 — 불균형 정산은 만들어질 수 없다.
     */
    public static Settlement of(LocalDate settlementDate, long grossAmount, long feeAmount,
                                long feeVatAmount, int itemCount, LocalDate payoutDate) {
        if (grossAmount < 0 || feeAmount < 0 || feeVatAmount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: gross=%d, fee=%d, feeVat=%d"
                    .formatted(grossAmount, feeAmount, feeVatAmount));
        }
        if (feeAmount + feeVatAmount > grossAmount) {
            throw new IllegalArgumentException("수수료+부가세가 총액보다 클 수 없습니다: gross=%d, fee=%d, feeVat=%d"
                    .formatted(grossAmount, feeAmount, feeVatAmount));
        }
        Settlement settlement = new Settlement(settlementDate, grossAmount, feeAmount, feeVatAmount, itemCount, payoutDate);
        if (settlement.netAmount != grossAmount - feeAmount - feeVatAmount) {
            throw new IllegalStateException("정산 불변식 위반: net(%d) ≠ gross(%d) - fee(%d) - feeVat(%d)"
                    .formatted(settlement.netAmount, grossAmount, feeAmount, feeVatAmount));
        }
        return settlement;
    }

    /**
     * 지급을 확정한다 — CREATED일 때만 PAID_OUT으로 전이하고 지급 확정 시각을 찍는다.
     *
     * <p>멱등: 이미 PAID_OUT이면 무시한다(중복 확정 방어). createdAt과 동일하게 {@code Instant.now()}로
     * 시각을 스냅샷한다.
     */
    public void markPaidOut() {
        if (this.status == SettlementStatus.CREATED) {
            this.status = SettlementStatus.PAID_OUT;
            this.paidOutAt = Instant.now();
        }
    }
}
