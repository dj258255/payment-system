package com.beomsu.pay.order;

import com.beomsu.pay.payment.ApprovalOutcome;
import com.beomsu.pay.payment.PaymentService;
import com.beomsu.pay.payment.StuckPaymentInfo;
import com.beomsu.pay.shared.Money;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 멈춘 체크아웃 사가 복구 — ADR-007 사가 이행의 "진짜 어려운 부분".
 *
 * <p>사가는 원자성을 포기한 대가로 새 크래시 엣지케이스가 생긴다: 예약(Phase 1)이 커밋된 뒤 확정(Phase 3)
 * 전에 앱이 죽으면, 주문은 {@code PAYMENT_IN_PROGRESS}로, 결제는 IN_PROGRESS로, 포인트는 예약된 채 멈춘다.
 * 이 서비스가 그 멈춘 주문을 스캔해, 카드 결제를 PG 조회로 확정한 뒤 {@link CheckoutTx#settle}을 재실행해
 * 주문을 완결(승인됐으면 재고차감·PAID)하거나 롤백(실패면 포인트 복원·PENDING 복귀)한다.
 *
 * <p>재진입 안전: {@code settle}은 {@code applyResult}가 멱등(IN_PROGRESS일 때만 전이)이라 여러 번 돌아도
 * 안전하다. 카드 결제가 없는 전액 포인트 주문은 PG 조회 없이 완결한다(포인트가 곧 결제).
 */
@Service
@RequiredArgsConstructor
public class CheckoutRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutRecoveryService.class);

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final CheckoutTx checkoutTx;

    /** 이 시간 이상 PAYMENT_IN_PROGRESS로 머문 주문만 복구 대상 — 진행 중인 정상 체크아웃과 겹치지 않게. */
    @Value("${app.checkout.recovery.stuck-after-minutes:10}")
    private long stuckAfterMinutes;

    /** 멈춘 체크아웃을 스캔해 완결/롤백한다. 반환값은 처리한 건수. */
    public int recoverStuckCheckouts() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(stuckAfterMinutes));
        List<Order> stuck = orderRepository.findByStatusAndUpdatedAtBefore(
                OrderStatus.PAYMENT_IN_PROGRESS, threshold);

        int recovered = 0;
        for (Order order : stuck) {
            try {
                // 카드 결제를 PG 조회로 확정(없으면 전액 포인트 → empty).
                Optional<StuckPaymentInfo> info = paymentService.resolveStuckPayment(order.getOrderNo());
                long cardAmount = info.map(StuckPaymentInfo::amount).orElse(0L);
                // 포인트분은 금액 검증 불변식(카드+포인트=총액)에서 도출한다.
                long pointAmount = order.getTotalAmount() - cardAmount;
                ApprovalOutcome outcome = info.map(StuckPaymentInfo::outcome).orElse(null);
                Long paymentId = info.map(StuckPaymentInfo::paymentId).orElse(null);

                checkoutTx.settle(order.getOrderNo(), paymentId, Money.of(cardAmount), pointAmount, outcome);
                recovered++;
            } catch (Exception e) {
                // 한 건 실패가 배치를 멈추지 않게 격리 — 다음 주기에 재시도.
                log.warn("멈춘 체크아웃 복구 실패 orderNo={} : {}", order.getOrderNo(), e.getMessage());
            }
        }
        if (recovered > 0) {
            log.info("멈춘 체크아웃 복구 완료 recovered={}", recovered);
        }
        return recovered;
    }
}
