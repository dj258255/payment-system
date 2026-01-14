package com.beomsu.pay.payment;

import com.beomsu.pay.payment.pg.PgClient;
import com.beomsu.pay.payment.pg.PgQueryResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 미확정(UNKNOWN) 결제 복구.
 *
 * <p>승인 API가 타임아웃되면 결제는 UNKNOWN으로 남는다(성공도 실패도 아님). 이 서비스가 주기적으로
 * 그런 결제를 스캔해 <b>PG 조회 API로 실제 상태를 확정</b>한다.
 * <ul>
 *   <li>PG에 승인돼 있으면 → 전진 복구(DONE) + 결제 완료 이벤트 발행</li>
 *   <li>PG에 없으면 → 승인이 실제로 안 된 것 → ABORTED</li>
 *   <li>이미 취소됨 → CANCELED로 반영</li>
 * </ul>
 * {@link PaymentRecoveryScheduler}가 {@code app.recovery.enabled=true}일 때 이 로직을 주기 실행한다(운영).
 * 테스트는 {@link #recoverUnknownPayments()}를 직접 호출한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    /** 이 시간 이상 UNKNOWN으로 머문 결제만 복구 대상 — 진행 중인 정상 요청과 겹치지 않게 한다. */
    private static final Duration MIN_AGE = Duration.ofMinutes(1);

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final ApplicationEventPublisher events;

    /** UNKNOWN 결제를 스캔해 확정한다. 반환값은 처리한 건수. */
    @Transactional
    public int recoverUnknownPayments() {
        Instant threshold = Instant.now().minus(MIN_AGE);
        List<Payment> targets = paymentRepository
                .findByStatusAndRequestedAtBefore(PaymentStatus.UNKNOWN, threshold);

        int recovered = 0;
        for (Payment payment : targets) {
            try {
                resolve(payment);
                recovered++;
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 멈추지 않게 한다. 다음 주기에 다시 시도된다.
                log.warn("결제 복구 실패 paymentId={} : {}", payment.getId(), e.getMessage());
            }
        }
        return recovered;
    }

    /**
     * paymentKey 하나를 PG 조회로 확정한다. 웹훅 수신 시 "페이로드를 믿지 말고 조회로 재검증"하는
     * 경로가 이 메서드를 쓴다. 이미 확정된(DONE/CANCELED 등) 결제면 아무 것도 하지 않는다(멱등).
     */
    @Transactional
    public void resolveByPaymentKey(String paymentKey) {
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentKey));
        if (payment.getStatus() != PaymentStatus.UNKNOWN
                && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            return; // 이미 확정됨 — 웹훅이 늦게/중복 도착해도 안전(멱등)
        }
        resolve(payment);
    }

    private void resolve(Payment payment) {
        PgQueryResult pg = pgClient.query(payment.getPaymentKey());
        // 상태 전이를 명시적으로 영속한다. OSIV off 환경에서 detached 엔티티는 dirty-checking 자동
        // flush가 일어나지 않으므로, 확정 상태가 DB에 반영되도록(APPROVED는 이벤트 발행 전에) 저장한다.
        switch (pg.status()) {
            case APPROVED -> {
                payment.confirmByRecovery(pg.method());
                paymentRepository.saveAndFlush(payment);
                events.publishEvent(new PaymentConfirmedEvent(
                        payment.getOrderNo(), payment.getId(),
                        payment.getAmount(), payment.getApprovedAt()));
            }
            case NOT_FOUND -> {
                payment.abortByRecovery("복구: PG에 결제 정보 없음(승인 미완료)");
                paymentRepository.saveAndFlush(payment);
            }
            case CANCELED -> {
                payment.networkCancel("복구: PG에서 이미 취소됨");
                paymentRepository.saveAndFlush(payment);
            }
        }
    }
}
