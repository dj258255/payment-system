package com.beomsu.pay.order;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 주문 만료 배치.
 *
 * <p>{@code Order.markExpired()} 로직은 있으나 이를 주기 호출하는 경로가 없어, PENDING_PAYMENT로
 * 유효시간(30분)이 지난 주문이 EXPIRED로 넘어가지 못하고 방치됐다. 이 서비스가 그런 주문을 스캔해
 * 만료시킨다. 건별 예외를 격리해 한 건 실패가 배치 전체를 멈추지 않게 하고(다음 주기 재시도),
 * 처리한 건수를 반환한다({@code VirtualAccountService.expireOverdue}·{@code PaymentRecoveryService}와 동일 패턴).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final OrderRepository orderRepository;

    /**
     * PENDING_PAYMENT이며 만료 예정 시각이 지난 주문을 EXPIRED로 전이한다. 반환값은 처리한 건수.
     */
    public int expireOverdue(Instant now) {
        List<Order> targets =
                orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, now);

        int processed = 0;
        for (Order order : targets) {
            try {
                order.markExpired();
                // 상태 전이(EXPIRED)를 명시적으로 영속한다. OSIV off 환경에서 detached 엔티티는
                // dirty-checking 자동 flush가 일어나지 않으므로 저장을 명시한다(flush 강제).
                orderRepository.saveAndFlush(order);
                processed++;
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 멈추지 않게 한다. 다음 주기에 다시 시도된다.
                log.warn("주문 만료 처리 실패 orderNo={} : {}", order.getOrderNo(), e.getMessage());
            }
        }
        return processed;
    }
}
