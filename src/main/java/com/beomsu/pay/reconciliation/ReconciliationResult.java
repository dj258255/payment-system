package com.beomsu.pay.reconciliation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 대사 결과 — 내부/외부 기록을 매칭한 판정 1건.
 *
 * <p>4분류({@link ReconResultType})와 후속 상태({@link ReconStatus})를 남긴다. 일치 건은
 * 자동 종결(AUTO_RESOLVED), 불일치 건은 예외 큐(PENDING)로 사람 확인을 기다린다.
 * 정적 팩토리로만 만들어, 분류마다 어떤 금액 필드가 채워지는지를 강제한다.
 */
@Entity
@Table(name = "reconciliation_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부에만 있는 경우에도 외부 orderNo를 기록하므로 사실상 채워지나, 스키마상 nullable 허용 */
    @Column(length = 64)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconResultType result;

    /** 내부 기록 금액 — 외부에만 있으면 null */
    @Column
    private Long internalAmount;

    /** 외부 기록 금액 — 내부에만 있으면 null */
    @Column
    private Long externalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconStatus status;

    @Column(nullable = false)
    private Instant reconciledAt;

    private ReconciliationResult(String orderNo, ReconResultType result,
                                 Long internalAmount, Long externalAmount, ReconStatus status) {
        this.orderNo = orderNo;
        this.result = result;
        this.internalAmount = internalAmount;
        this.externalAmount = externalAmount;
        this.status = status;
        this.reconciledAt = Instant.now();
    }

    /** 양쪽 일치 — 자동 종결. */
    public static ReconciliationResult matched(String orderNo, long amount) {
        return new ReconciliationResult(orderNo, ReconResultType.MATCHED, amount, amount, ReconStatus.AUTO_RESOLVED);
    }

    /** 내부에만 있음(PG 누락 의심) — 사람 확인 필요. */
    public static ReconciliationResult internalOnly(String orderNo, long internalAmount) {
        return new ReconciliationResult(orderNo, ReconResultType.INTERNAL_ONLY, internalAmount, null, ReconStatus.PENDING);
    }

    /** 외부에만 있음(내부 유실 의심) — 사람 확인 필요. */
    public static ReconciliationResult externalOnly(String orderNo, long externalAmount) {
        return new ReconciliationResult(orderNo, ReconResultType.EXTERNAL_ONLY, null, externalAmount, ReconStatus.PENDING);
    }

    /** 양쪽에 있으나 금액 불일치 — 사람 확인 필요. */
    public static ReconciliationResult amountMismatch(String orderNo, long internalAmount, long externalAmount) {
        return new ReconciliationResult(orderNo, ReconResultType.AMOUNT_MISMATCH, internalAmount, externalAmount, ReconStatus.PENDING);
    }

    /**
     * 수기 확정 — 사람이 예외 큐(PENDING)를 검토한 뒤 종결 처리한다. PENDING이 아니면(이미 종결) 예외.
     *
     * <p>누가/왜 확정했는지는 엔티티에 컬럼으로 남기지 않고 어드민 <b>감사 로그</b>로 남긴다
     * (스키마 변경 최소화). 상태만 MANUALLY_RESOLVED로 전이한다.
     */
    public void resolveManually() {
        if (status != ReconStatus.PENDING) {
            throw ReconciliationException.notPending(status);
        }
        this.status = ReconStatus.MANUALLY_RESOLVED;
    }
}
