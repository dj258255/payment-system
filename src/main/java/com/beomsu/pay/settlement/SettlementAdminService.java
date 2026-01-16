package com.beomsu.pay.settlement;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 정산 운영 어드민 — 정산 집계를 조회하고, 가맹점 지급을 확정하며, 정산 배치를 수동 실행한다.
 *
 * <p>그동안 {@code settle()} 배치는 테스트에서만 호출되고 정산을 볼 어드민도 없었다. 이 어드민이
 * <b>조회 → 지급 확정</b> 루프를 닫고, 데모·수동 운영을 위한 {@link #runSettlement(LocalDate)}로
 * 배치를 즉시 돌릴 수 있게 한다(주기 실행은 {@link SettlementScheduler}).
 */
@Service
@RequiredArgsConstructor
public class SettlementAdminService {

    private final SettlementRepository repository;
    private final SettlementService settlementService;

    /** 정산 집계 페이지(뷰 record로 노출). */
    @Transactional(readOnly = true)
    public Page<SettlementView> list(Pageable pageable) {
        return repository.findAll(pageable).map(SettlementView::from);
    }

    /**
     * 정산 1건의 지급을 확정한다(CREATED → PAID_OUT).
     *
     * <p>OSIV off 환경이라 finder로 로드한 엔티티의 dirty-checking 변경은 자동 flush되지 않는다.
     * 따라서 {@link Settlement#markPaidOut()} 후 반드시 {@code saveAndFlush}로 명시 영속한다.
     * markPaidOut은 멱등이라 이미 PAID_OUT이면 시각을 덮어쓰지 않는다.
     */
    @Transactional
    public SettlementView confirmPayout(long settlementId) {
        Settlement settlement = repository.findById(settlementId)
                .orElseThrow(() -> SettlementException.notFound(settlementId));
        settlement.markPaidOut();
        repository.saveAndFlush(settlement);
        return SettlementView.from(settlement);
    }

    /**
     * 정산 배치를 수동 실행한다(데모·수동 운영용) — {@link SettlementService#settle(LocalDate)}에 위임한다.
     *
     * @return 생성된 정산, 재실행(그 날짜 정산 존재)이거나 대상 CONFIRMED 항목이 없으면 null
     */
    @Transactional
    public Settlement runSettlement(LocalDate date) {
        return settlementService.settle(date);
    }
}
