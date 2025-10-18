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
 * <p>불변식: <b>netAmount = grossAmount - feeAmount</b>. 생성 시점에 강제하며, 위반하면 예외가 난다.
 * {@code settlementDate} 유니크로 같은 날짜 정산이 두 번 만들어지는 것을 DB가 차단한다 —
 * 배치 재실행 멱등성의 핵심.
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

    /** 지급액 = gross - fee (불변식 검증 대상) */
    @Column(nullable = false)
    private long netAmount;

    @Column(nullable = false)
    private int itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Settlement(LocalDate settlementDate, long grossAmount, long feeAmount, int itemCount) {
        this.settlementDate = settlementDate;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = grossAmount - feeAmount;
        this.itemCount = itemCount;
        this.status = SettlementStatus.CREATED;
        this.createdAt = Instant.now();
    }

    /**
     * 하루치 집계로 정산을 만든다. netAmount는 gross-fee로 계산하며, 불변식(net = gross - fee)을
     * 생성 직후 재검증한다 — 불균형 정산은 만들어질 수 없다.
     */
    public static Settlement of(LocalDate settlementDate, long grossAmount, long feeAmount, int itemCount) {
        if (grossAmount < 0 || feeAmount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: gross=%d, fee=%d".formatted(grossAmount, feeAmount));
        }
        if (feeAmount > grossAmount) {
            throw new IllegalArgumentException("수수료가 총액보다 클 수 없습니다: gross=%d, fee=%d".formatted(grossAmount, feeAmount));
        }
        Settlement settlement = new Settlement(settlementDate, grossAmount, feeAmount, itemCount);
        if (settlement.netAmount != grossAmount - feeAmount) {
            throw new IllegalStateException("정산 불변식 위반: net(%d) ≠ gross(%d) - fee(%d)"
                    .formatted(settlement.netAmount, grossAmount, feeAmount));
        }
        return settlement;
    }
}
