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
}
