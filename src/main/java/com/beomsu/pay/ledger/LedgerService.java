package com.beomsu.pay.ledger;

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

    private final LedgerTransactionRepository repository;

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

    private boolean alreadyRecorded(String txType, long paymentId) {
        return repository.existsByTxTypeAndSourceTypeAndSourceId(txType, SOURCE_PAYMENT, paymentId);
    }
}
