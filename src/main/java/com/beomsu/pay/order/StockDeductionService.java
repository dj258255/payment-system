package com.beomsu.pay.order;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감 전략 3종 — Phase 5 동시성 비교 실험의 대상.
 *
 * <p>세 방식 모두 "재고가 음수가 되지 않는다"는 정합성은 지킨다. 차이는 성능·경합 처리 방식이다.
 * 부하테스트로 TPS·에러율을 비교해 선택 근거를 남긴다(ADR-004).
 * <ul>
 *   <li><b>비관적 락</b>: SELECT FOR UPDATE. 충돌 잦을 때 확실하지만 커넥션을 오래 점유한다.</li>
 *   <li><b>낙관적 락</b>: @Version + 재시도. 충돌 드물 때 유리, 잦으면 재시도 폭증.</li>
 *   <li><b>조건부 UPDATE</b>: 락 없이 원자적. 대개 가장 저비용 — 이 프로젝트의 기본 선택.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StockDeductionService {

    private final StockRepository stockRepository;

    /** 조건부 UPDATE — 기본 전략. 영향 행 0이면 재고 부족. */
    @Transactional
    public void deductConditional(long productId, int qty) {
        int updated = stockRepository.deductConditionally(productId, qty);
        if (updated == 0) {
            throw OrderException.outOfStock(productId);
        }
    }

    /** 비관적 락 — 행을 잠그고 차감. */
    @Transactional
    public void deductPessimistic(long productId, int qty) {
        Stock stock = stockRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> OrderException.outOfStock(productId));
        stock.deduct(qty);
    }

    private static final int MAX_RETRY = 10;

    /**
     * 낙관적 락 — @Version 충돌 시 재시도. 각 시도의 조회·저장은 별도 트랜잭션이며, 버전 충돌은
     * saveAndFlush 시점에 감지된다. 재시도를 다 소진하면 예외 — 고경합에서는 재시도 폭증으로
     * 정상 차감도 실패할 수 있다(낙관적 락의 특성). 자기호출 프록시 우회를 피하려고 루프를 인라인한다.
     */
    public void deductOptimisticWithRetry(long productId, int qty) {
        int attempts = 0;
        while (true) {
            try {
                Stock stock = stockRepository.findById(productId)
                        .orElseThrow(() -> OrderException.outOfStock(productId));
                stock.deduct(qty);
                stockRepository.saveAndFlush(stock); // 버전 충돌을 이 시점에 감지
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                if (++attempts >= MAX_RETRY) {
                    throw new OrderException("STOCK_CONCURRENCY", "재고 차감 경합이 계속됩니다: " + productId);
                }
            }
        }
    }
}
