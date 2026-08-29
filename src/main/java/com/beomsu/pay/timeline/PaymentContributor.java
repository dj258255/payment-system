package com.beomsu.pay.timeline;

import com.beomsu.pay.payment.PaymentTimelineFacts;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 결제 전이 이력을 타임라인에 싣는다.
 *
 * <p>이 프로젝트에서 <b>유일하게 "언제 무엇에서 무엇으로 바뀌었는가"를 아는</b> 출처다.
 * 대사 원인 판단에서 가장 중요한 재료이므로, 전이를 하나도 접지 않고 그대로 편다.
 */
@Component
class PaymentContributor implements TimelineContributor {

    private final PaymentTimelineFacts facts;

    PaymentContributor(PaymentTimelineFacts facts) {
        this.facts = facts;
    }

    @Override
    public List<TimelineEntry> contribute(String orderNo) {
        return facts.findByOrderNo(orderNo).stream()
                .map(f -> TimelineEntry.of(
                        f.at(), TimelineEntry.Source.PAYMENT,
                        "PAYMENT_" + f.toStatus(),
                        // 사유는 있을 때만 붙인다. 없는데 "()"만 남으면 읽는 사람이 헷갈린다.
                        f.reason() == null || f.reason().isBlank()
                                ? "결제 %s → %s (%s, %s)".formatted(f.fromStatus(), f.toStatus(), f.triggeredBy(), f.pgProvider())
                                : "결제 %s → %s (%s, %s) — %s".formatted(f.fromStatus(), f.toStatus(), f.triggeredBy(), f.pgProvider(), f.reason()),
                        f.amount()))
                .toList();
    }

    @Override
    public TimelineEntry.Source source() {
        return TimelineEntry.Source.PAYMENT;
    }
}
