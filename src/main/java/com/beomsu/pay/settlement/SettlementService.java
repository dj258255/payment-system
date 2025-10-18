package com.beomsu.pay.settlement;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 정산 서비스 — 결제 승인 적재 + 일 단위 집계.
 *
 * <p>적재: 결제 승인 이벤트를 정산 항목(PENDING)으로 쌓는다(paymentId 유니크로 멱등).
 * 집계: 특정 날짜의 PENDING 항목을 합산해 수수료(3%, 내림)를 떼고 지급금 정산을 만든다.
 * settlementDate 유니크로 배치를 같은 날짜로 재실행해도 정산이 중복 생성되지 않는다.
 *
 * <p>Phase 4는 서비스 루프로 집계하지만, 대용량은 Spring Batch로 확장한다
 * (chunk 단위 커밋 · cursor 기반 읽기 · 날짜/가맹점 partitioning).
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    /** 정산 수수료율: 총액의 3%를 내림(floor)한다. KRW는 소수점이 없으므로 정수 연산으로 확정. */
    private static final long FEE_PERCENT = 3;

    private final SettlementItemRepository itemRepository;
    private final SettlementRepository settlementRepository;

    /**
     * 결제 승인 이벤트를 정산 항목으로 적재한다.
     *
     * <p>멱등: 같은 paymentId가 이미 적재됐으면 아무 것도 하지 않는다. 승인 시각은 UTC 기준
     * 날짜({@code confirmedDate})로 스냅샷해, 일 단위 배치가 이 날짜로 집계한다.
     */
    @Transactional
    public void registerConfirmedPayment(PaymentConfirmedEvent event) {
        if (itemRepository.existsByPaymentId(event.paymentId())) {
            return; // 멱등: 이미 적재함
        }
        LocalDate confirmedDate = LocalDate.ofInstant(event.approvedAt(), ZoneOffset.UTC);
        itemRepository.save(SettlementItem.of(
                event.paymentId(), event.orderNo(), event.amount(), confirmedDate));
    }

    /**
     * 정산 배치의 핵심 — 해당 날짜의 PENDING 항목을 집계해 정산을 만든다.
     *
     * <p>흐름: PENDING 조회 → 총액(gross) 합산 → 수수료(3% 내림) → 정산 생성·저장 → 항목 SETTLED.
     * 재실행 멱등: 그 날짜 정산이 이미 있으면(existsBySettlementDate) 아무 것도 하지 않고 null 반환.
     * 집계할 PENDING 항목이 없어도 null 반환(빈 정산은 만들지 않는다).
     *
     * @return 생성된 정산, 재실행이거나 대상이 없으면 null
     */
    @Transactional
    public Settlement settle(LocalDate date) {
        if (settlementRepository.existsBySettlementDate(date)) {
            log.info("정산 재실행 감지 → 건너뜀 date={}", date);
            return null; // 멱등: 이미 그 날짜 정산이 존재
        }

        List<SettlementItem> items =
                itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.PENDING, date);
        if (items.isEmpty()) {
            return null; // 집계할 대상 없음 → 빈 정산을 만들지 않는다
        }

        long gross = 0L;
        for (SettlementItem item : items) {
            gross = Math.addExact(gross, item.getAmount()); // 오버플로 시 즉시 실패
        }
        long fee = calculateFee(gross);

        Settlement settlement = settlementRepository.save(
                Settlement.of(date, gross, fee, items.size()));
        items.forEach(SettlementItem::markSettled);
        return settlement;
    }

    /** 총액의 3%를 내림. floor(gross * 3 / 100)을 정수 연산으로 계산(오버플로는 Math.multiplyExact로 방어). */
    private long calculateFee(long gross) {
        return Math.multiplyExact(gross, FEE_PERCENT) / 100;
    }
}
