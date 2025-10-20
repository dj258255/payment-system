package com.beomsu.pay.reconciliation;

import com.beomsu.pay.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 대사 서비스 — 내부 기록 적재 + 결정적 매칭 엔진. "결제의 최종 방어선".
 *
 * <p>적재: 결제 승인 이벤트를 내부 기대치(InternalRecord)로 쌓는다(orderNo 유니크로 멱등).
 * 매칭: 내부 기록과 PG 정산 파일(외부)을 orderNo로 대조해 4분류(일치/내부만/외부만/금액불일치)한다.
 *
 * <p>매칭 엔진은 <b>결정적(deterministic)</b>이다 — 같은 입력(내부·외부 집합)이면 항상 같은 결과.
 * orderNo 정렬 순서로 결과를 만들어 재현·감사 가능하게 한다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final InternalRecordRepository internalRecords;
    private final ReconciliationResultRepository results;

    /**
     * 결제 승인 이벤트를 내부 기록으로 적재한다. 멱등: 같은 orderNo가 이미 있으면 건너뛴다.
     */
    @Transactional
    public void recordInternal(PaymentConfirmedEvent event) {
        if (internalRecords.existsByOrderNo(event.orderNo())) {
            return; // 멱등: 이미 적재함
        }
        internalRecords.save(InternalRecord.of(event.orderNo(), event.amount()));
    }

    /**
     * 대사 매칭 엔진 — 내부 기록 전체와 외부(PG 정산 파일) 목록을 orderNo로 대조해 4분류한다.
     *
     * <ul>
     *   <li>양쪽에 있고 금액 같음 → MATCHED (자동 종결)</li>
     *   <li>양쪽에 있고 금액 다름 → AMOUNT_MISMATCH (사람 확인)</li>
     *   <li>내부에만 → INTERNAL_ONLY (PG 누락 의심)</li>
     *   <li>외부에만 → EXTERNAL_ONLY (내부 유실 의심)</li>
     * </ul>
     *
     * <p>결정적: 모든 orderNo를 정렬한 순서로 판정하므로, 같은 입력이면 결과의 내용·순서가 항상 같다.
     * 각 판정을 {@link ReconciliationResult}로 저장하고 리스트로 돌려준다.
     */
    @Transactional
    public List<ReconciliationResult> reconcile(List<ExternalRecord> external) {
        Map<String, Long> internalMap = new LinkedHashMap<>();
        for (InternalRecord record : internalRecords.findAll()) {
            internalMap.put(record.getOrderNo(), record.getAmount());
        }
        Map<String, Long> externalMap = new LinkedHashMap<>();
        for (ExternalRecord record : external) {
            externalMap.put(record.orderNo(), record.amount());
        }

        // 양쪽 키의 합집합을 정렬 → 결정적 순서
        TreeSet<String> orderNos = new TreeSet<>();
        orderNos.addAll(internalMap.keySet());
        orderNos.addAll(externalMap.keySet());

        List<ReconciliationResult> reconciled = new ArrayList<>();
        for (String orderNo : orderNos) {
            Long internalAmount = internalMap.get(orderNo);
            Long externalAmount = externalMap.get(orderNo);

            ReconciliationResult result;
            if (internalAmount != null && externalAmount != null) {
                result = internalAmount.longValue() == externalAmount.longValue()
                        ? ReconciliationResult.matched(orderNo, internalAmount)
                        : ReconciliationResult.amountMismatch(orderNo, internalAmount, externalAmount);
            } else if (internalAmount != null) {
                result = ReconciliationResult.internalOnly(orderNo, internalAmount);
            } else {
                result = ReconciliationResult.externalOnly(orderNo, externalAmount);
            }
            reconciled.add(result);
        }

        results.saveAll(reconciled);
        return reconciled;
    }
}
