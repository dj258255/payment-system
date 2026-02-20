package com.beomsu.pay.ledger;

import com.beomsu.pay.dispute.DisputeLostEvent;
import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 원장 서비스 — 결제 사건을 복식부기 분개로 기록한다.
 *
 * <p>결제 승인: PG 미수금(차변) ↔ 매출(대변). 취소: 그 역분개. 원거래를 지우지 않고 반대 분개를
 * 추가해 이력을 보존한다. (txType, sourceType, sourceId) 유니크로 같은 이벤트의 중복 분개를 막는다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final String SOURCE_PAYMENT = "PAYMENT";
    private static final String SOURCE_DISPUTE = "DISPUTE";

    private final LedgerTransactionRepository repository;

    /** 최근 원장 트랜잭션 조회(감사용) — 분개 목록·균형 여부 포함. */
    @Transactional(readOnly = true)
    public List<LedgerView> recentTransactions() {
        return repository.findTop50ByOrderByIdDesc().stream()
                .map(LedgerView::from)
                .toList();
    }

    @Transactional
    public void recordPaymentConfirmed(PaymentConfirmedEvent event) {
        if (alreadyRecorded("PAYMENT_APPROVED", event.paymentId())) {
            return; // 멱등: 이미 분개함
        }
        long amount = event.amount();
        LedgerTransaction tx = LedgerTransaction.of(
                "PAYMENT_APPROVED", SOURCE_PAYMENT, event.paymentId(),
                "결제 승인 " + event.orderNo(),
                List.of(
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, amount),
                        LedgerEntry.credit(AccountType.SALES, amount)
                ));
        repository.save(tx);
    }

    @Transactional
    public void recordPaymentCanceled(PaymentCanceledEvent event) {
        // 취소 건마다 별도 소스 키를 쓴다(부분취소가 여러 번일 수 있으므로 취소 금액 기반 구분).
        long cancelAmount = event.cancelAmount();
        if (alreadyRecorded("PAYMENT_CANCELED", event.paymentId())) {
            return;
        }
        LedgerTransaction tx = LedgerTransaction.of(
                "PAYMENT_CANCELED", SOURCE_PAYMENT, event.paymentId(),
                "결제 취소 " + event.orderNo(),
                List.of(
                        LedgerEntry.debit(AccountType.SALES, cancelAmount),          // 역분개
                        LedgerEntry.credit(AccountType.PG_RECEIVABLE, cancelAmount)
                ));
        repository.save(tx);
    }

    /**
     * 분쟁 패소 → 원매출 역분개. 취소({@link #recordPaymentCanceled})와 같은 방향으로 되돌린다:
     * 매출(차변) ↔ PG 미수금(대변). 원거래를 지우지 않고 반대 분개를 추가해 이력을 보존한다.
     * (txType="DISPUTE_LOST", sourceType="DISPUTE", sourceId=disputeId) 유니크로 <b>멱등</b> —
     * 같은 패소 이벤트가 재전달돼도 역분개는 한 번만.
     */
    @Transactional
    public void recordDisputeLost(DisputeLostEvent event) {
        if (repository.existsByTxTypeAndSourceTypeAndSourceId("DISPUTE_LOST", SOURCE_DISPUTE, event.disputeId())) {
            return; // 멱등: 이미 역분개함
        }
        long amount = event.amount();
        LedgerTransaction tx = LedgerTransaction.of(
                "DISPUTE_LOST", SOURCE_DISPUTE, event.disputeId(),
                "분쟁 패소 역분개 " + event.orderNo(),
                List.of(
                        LedgerEntry.debit(AccountType.SALES, amount),          // 역분개
                        LedgerEntry.credit(AccountType.PG_RECEIVABLE, amount)
                ));
        repository.save(tx);
    }

    private boolean alreadyRecorded(String txType, long paymentId) {
        return repository.existsByTxTypeAndSourceTypeAndSourceId(txType, SOURCE_PAYMENT, paymentId);
    }
}
