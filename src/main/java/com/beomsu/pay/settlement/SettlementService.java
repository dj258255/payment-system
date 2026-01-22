package com.beomsu.pay.settlement;

import com.beomsu.pay.escrow.EscrowReleasedEvent;
import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 정산 서비스 — 에스크로 생명주기에 정렬된 적재·확정·집계.
 *
 * <p><b>도메인 정렬</b>: 에스크로는 "구매확정 전까지 판매자 정산 보류"를 약속한다. 따라서 결제 승인은
 * 정산 항목을 곧바로 정산 가능으로 만들지 않고 {@code PENDING_CONFIRMATION}(구매확정 대기)으로만 적재한다.
 * 에스크로 릴리스(={@link EscrowReleasedEvent}, 구매확정)가 와야 {@code CONFIRMED}(정산 가능)로 전이하고,
 * 배치는 <b>CONFIRMED만</b> 집계해 {@code SETTLED}로 넘긴다 — 구매확정 전 항목은 지급에서 빠진다(보류의 실현).
 *
 * <p><b>취소 반영</b>: 취소 이벤트를 구독해 정산액을 되돌린다. 전액취소는 항목을 CANCELED로 제외하고,
 * 부분취소는 금액을 차감한다. 단 이미 SETTLED(집계 완료)된 항목은 정산에서 되돌릴 수 없으므로 카운터로
 * 기록하고 운영이 별도 정산 조정으로 처리하게 남긴다(아래 {@link #reflectCancellation} 참고).
 *
 * <p>서비스 루프로 집계하지만, 대용량은 Spring Batch로 확장한다
 * (chunk 단위 커밋 · cursor 기반 읽기 · 날짜/가맹점 partitioning).
 *
 * <p><b>후속 과제</b>: 수수료(fee)·수수료 부가세(feeVat)를 원장(ledger) 비용 계정으로 분개하는 것은
 * 이번 범위 밖이다(모듈 경계·이벤트 추가 리스크). 정산은 원장으로부터 재구성 가능한 집계로 남긴다.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementItemRepository itemRepository;
    private final SettlementRepository settlementRepository;
    private final MeterRegistry meterRegistry;

    /** 정산 수수료율(basis point). 270 bps = 2.7%. KRW는 소수점이 없으므로 정수 연산으로 확정. */
    private final long feeBps;

    /** 지급예정일 = settlementDate + 이 영업일 수(주말 skip). */
    private final int payoutDays;

    public SettlementService(SettlementItemRepository itemRepository,
                             SettlementRepository settlementRepository,
                             MeterRegistry meterRegistry,
                             @Value("${app.settlement.fee-bps:270}") long feeBps,
                             @Value("${app.settlement.payout-business-days:2}") int payoutDays) {
        this.itemRepository = itemRepository;
        this.settlementRepository = settlementRepository;
        this.meterRegistry = meterRegistry;
        this.feeBps = feeBps;
        this.payoutDays = payoutDays;
    }

    /**
     * 결제 승인 이벤트를 정산 항목으로 적재한다 — 상태는 PENDING_CONFIRMATION(구매확정 대기).
     *
     * <p>멱등: 같은 paymentId가 이미 적재됐으면 아무 것도 하지 않는다. 승인 시각은 UTC 기준
     * 날짜({@code confirmedDate})로 스냅샷해, 일 단위 배치가 이 날짜로 집계한다.
     */
    @Transactional
    public void registerConfirmedPayment(PaymentConfirmedEvent event) {
        if (itemRepository.existsByPaymentId(event.paymentId())) {
            return; // 멱등: 이미 적재함
        }
        // 적재 시점의 confirmedDate는 승인일 placeholder다(PENDING은 집계 대상이 아님). 실제 집계
        // 기준일은 구매확정(릴리스) 시 confirmSettlement가 릴리스일로 재스탬프한다.
        LocalDate approvalDate = LocalDate.ofInstant(event.approvedAt(), ZoneOffset.UTC);
        // 최초 INSERT는 save로 충분(신규 영속 → flush는 트랜잭션 커밋이 처리).
        itemRepository.save(SettlementItem.of(
                event.paymentId(), event.orderNo(), event.amount(), approvalDate));
    }

    /**
     * 에스크로 릴리스(구매확정) → 정산 항목을 CONFIRMED로 전이하고 집계 기준일을 <b>릴리스일로
     * 재스탬프</b>한다. <b>죽어 있던 {@link EscrowReleasedEvent}를 구독해 정산을 구매확정 시점으로
     * 옮기는 핵심.</b>
     *
     * <p>재스탬프가 필수다 — 에스크로 홀드는 다일(기본 7일)이라 승인일 D의 항목이 D+7에 CONFIRMED된다.
     * 승인일 그대로 두면 {@code settle(D)}는 D+1에 이미 지났고 재실행도 멱등 skip돼 <b>영구 미정산</b>이
     * 된다. 릴리스일 R로 재스탬프하면 R+1의 {@code settle(R)}이 정확히 집계한다.
     *
     * <p>항목이 없으면 warn 후 return한다 — 승인/릴리스 이벤트 순서 레이스(릴리스가 적재보다 먼저 도착)
     * 방어. Outbox at-least-once라 릴리스는 재전달되므로, 다음 배달에서 적재된 항목을 만나 전이한다.
     * 이 트랜잭션 안에서 로드한 엔티티라 커밋 시 dirty-check flush되지만, 전이 의도를 명시하려 saveAndFlush한다.
     *
     * @param orderNo     릴리스된 주문 번호
     * @param releaseDate 릴리스(구매확정)가 일어난 날짜 — 정산 집계 기준일로 재스탬프된다
     */
    @Transactional
    public void confirmSettlement(String orderNo, LocalDate releaseDate) {
        Optional<SettlementItem> found = itemRepository.findByOrderNo(orderNo);
        if (found.isEmpty()) {
            log.warn("에스크로 릴리스 수신했으나 정산 항목 없음 orderNo={} — 이벤트 순서 레이스, 재전달 대기", orderNo);
            return;
        }
        SettlementItem item = found.get();
        item.confirm(releaseDate); // 멱등: PENDING_CONFIRMATION일 때만 CONFIRMED + 집계일 재스탬프
        itemRepository.saveAndFlush(item);
    }

    /**
     * 결제 취소를 정산에 반영한다 — 전액취소는 제외(CANCELED), 부분취소는 금액 차감.
     *
     * <p>SETTLED 항목의 한계(정직한 처리): 이미 그날 정산에 집계돼 지급 대상이 된 항목은 여기서 되돌릴 수
     * 없다. 금액을 함부로 줄이면 이미 산출된 지급금과 어긋나므로, 항목은 건드리지 않고
     * {@code settlement.postsettle.cancel} 카운터만 올린다. 운영이 이 지표로 사후 정산 조정(차기 정산에서
     * 역분개 반영)을 수행한다. 원장(ledger)이 취소 역분개 이력을 이미 보유하므로 정산은 재구성 가능한 집계다.
     *
     * <p>부분취소의 at-least-once 한계: 같은 취소 이벤트가 중복 배달되면 {@code reduce}가 두 번 호출돼
     * 이중 차감될 수 있다. 정산은 원장으로부터 재구성 가능한 집계라는 판단 아래 이 위험을 한계로 남긴다
     * (엄밀한 방어는 취소 이벤트 멱등 키를 정산 측에 별도 적재하는 것 — Phase 5 범위).
     */
    @Transactional
    public void reflectCancellation(PaymentCanceledEvent event) {
        Optional<SettlementItem> found = itemRepository.findByPaymentId(event.paymentId());
        if (found.isEmpty()) {
            return; // 정산에 잡히지 않은 결제(비-정산 대상 등) — skip
        }
        SettlementItem item = found.get();

        if (item.getStatus() == SettlementItemStatus.SETTLED) {
            // 이미 지급 대상으로 집계됨 → 정산에서 차감 불가. 운영이 별도 정산 조정 필요.
            log.warn("정산 완료(SETTLED) 후 취소 수신 — 정산 차감 불가, 사후 조정 대상 paymentId={} orderNo={}",
                    event.paymentId(), event.orderNo());
            meterRegistry.counter("settlement.postsettle.cancel").increment();
            return;
        }
        if (item.getStatus() == SettlementItemStatus.CANCELED) {
            return; // 멱등: 이미 취소 제외됨
        }

        if (event.fullyCanceled()) {
            item.cancel(); // PENDING_CONFIRMATION/CONFIRMED → CANCELED
        } else {
            item.reduce(event.cancelAmount());
        }
        itemRepository.saveAndFlush(item);
    }

    /**
     * 정산 배치의 핵심 — 해당 날짜의 <b>CONFIRMED</b> 항목을 집계해 정산을 만든다.
     *
     * <p>흐름: CONFIRMED 조회 → 총액(gross) 합산 → 수수료(feeBps 내림)·부가세 → 지급예정일(영업일)
     * → 정산 생성·저장 → 항목 SETTLED. 구매확정 안 된 PENDING_CONFIRMATION은 집계에서 빠진다 —
     * 이것이 "구매확정 전 보류"의 실현이다. 재실행 멱등: 그 날짜 정산이 이미 있으면
     * (existsBySettlementDate) 아무 것도 하지 않고 null 반환. 집계할 CONFIRMED 항목이 없어도
     * null 반환(빈 정산은 만들지 않는다).
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
                itemRepository.findByStatusAndConfirmedDate(SettlementItemStatus.CONFIRMED, date);
        if (items.isEmpty()) {
            return null; // 집계할 대상 없음 → 빈 정산을 만들지 않는다
        }

        long gross = 0L;
        for (SettlementItem item : items) {
            gross = Math.addExact(gross, item.getAmount()); // 오버플로 시 즉시 실패
        }
        long fee = calculateFee(gross);
        long feeVat = fee / 10; // 수수료 부가세 10%(내림)
        LocalDate payoutDate = BusinessDays.plusBusinessDays(date, payoutDays);

        Settlement settlement = settlementRepository.save(
                Settlement.of(date, gross, fee, feeVat, items.size(), payoutDate));
        items.forEach(SettlementItem::markSettled);
        return settlement;
    }

    /**
     * 수수료를 basis point로 계산해 내림한다. floor(gross * feeBps / 10000)을 정수 연산으로 확정한다
     * (double 금지 — 화폐 정수 연산 원칙). 오버플로는 {@code Math.multiplyExact}로 방어한다.
     *
     * <p>수수료 부가세를 원장 비용 계정으로 분개하는 것은 후속 과제로 남긴다(모듈 경계 밖).
     */
    private long calculateFee(long gross) {
        return Math.multiplyExact(gross, feeBps) / 10000;
    }
}
