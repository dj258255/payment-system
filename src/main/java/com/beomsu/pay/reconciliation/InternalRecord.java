package com.beomsu.pay.reconciliation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 내부 기록 — 대사에서 "이만큼 들어왔어야 한다"는 결제 기대치.
 *
 * <p>결제 승인 이벤트를 구독해 쌓는다. {@code orderNo} 유니크로 같은 주문이 두 번 쌓이는 것을
 * DB가 차단한다 — 적재 멱등성. 대사 시 이 기록과 PG 정산 파일을 orderNo로 매칭한다.
 */
@Entity
@Table(name = "internal_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_internal_record_order", columnNames = {"orderNo"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private Instant recordedAt;

    private InternalRecord(String orderNo, long amount) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.recordedAt = Instant.now();
    }

    /** 결제 승인 기대치를 내부 기록으로 만든다. */
    public static InternalRecord of(String orderNo, long amount) {
        return new InternalRecord(orderNo, amount);
    }

    /**
     * 취소를 반영해 기대치를 낮춘다.
     *
     * <p>취소 후 잔액(절대값)으로 세팅한다. 델타를 빼면 같은 취소가 두 번 배달될 때 두 번 깎이지만,
     * 절대값이면 몇 번을 받아도 결과가 같다(멱등). 정산이 쓰는 방식과 같은 규칙이다.
     *
     * <p>이게 없으면 <b>취소된 모든 건이 영구 불일치로 남는다.</b> 부분취소는 매번 금액 불일치로,
     * 전액취소는 PG 정산 파일에서 빠지므로 "내부에만 있음"으로 잡힌다. 대사가 매일 같은 건을 다시
     * 올리면 예외 큐가 무의미해진다.
     */
    void applySettleableBalance(long settleableBalance) {
        this.amount = settleableBalance;
    }
}
