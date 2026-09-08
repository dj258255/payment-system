package com.beomsu.pay.assist.fraudreview;

import com.beomsu.pay.assist.draft.FactPack;
import com.beomsu.pay.assist.draft.NumericProvenanceGuard;
import com.beomsu.pay.fraud.FraudReviewFacts;
import com.beomsu.pay.fraud.FraudReviewFactsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 심사 초안을 만들어 준다. <b>가드에 걸리면 버리고 템플릿으로 떨어뜨린다.</b>
 *
 * <p>구조는 {@code assist.draft.DraftService} 와 같다. 그쪽에서 이미 겪은 것을 그대로 가져왔다.
 *
 * <pre>
 *   FraudReviewFacts      ← 코드가 조립한 사실. 문장 없음
 *         ↓
 *   FraudReviewDraftPort  ← 템플릿이든 모델이든 여기서 갈린다
 *         ↓
 *   NumericProvenanceGuard ← 초안의 금액·날짜가 사실에 있는지 대조
 *         ↓
 *   심사자가 읽고 승인·거부를 누른다
 * </pre>
 *
 * <p><b>왜 가드를 다시 쓰나.</b> 지어낸 금액은 근거 있는 금액과 문장에서 구별되지 않는다.
 * "결제 320,000원" 과 "결제 302,000원" 은 읽는 사람에게 똑같이 그럴듯하다. 심사자도 그 숫자가
 * 어디서 왔는지 모르므로 사람 검토에 맡길 수 없다.
 *
 * <p><b>가드가 잡는 것은 출처뿐이다.</b> 초안에 나온 숫자가 코드가 낸 값인지만 본다. 그 숫자가
 * 맞는 주장에 쓰였는지는 못 본다. 상황 3.3 에서 확인한 대로 <b>가드를 다 통과하고도 틀린 주장이
 * 나올 수 있다.</b> 그래서 판정은 사람이 누른다는 전제가 여기서도 유지된다.
 */
@Slf4j
@Service
public class FraudReviewDraftService {

    /** 알림이 보는 이름. {@code assist.incident.outcome} 과 규칙을 맞춘다. */
    public static final String METRIC = "assist.fraud_review.outcome";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FraudReviewFactsPort facts;
    private final FraudReviewDraftPort primary;
    private final TemplateFraudReviewAdapter template;
    private final NumericProvenanceGuard guard;
    private final MeterRegistry registry;

    public FraudReviewDraftService(FraudReviewFactsPort facts,
                                   FraudReviewDraftPort primary,
                                   TemplateFraudReviewAdapter template,
                                   NumericProvenanceGuard guard,
                                   MeterRegistry registry) {
        this.facts = facts;
        this.primary = primary;
        this.template = template;
        this.guard = guard;
        this.registry = registry;
    }

    /**
     * 심사 한 건의 초안.
     *
     * @return 초안과 출처. 그런 심사가 없으면 {@link Optional#empty()} —
     *         <b>초안을 못 만든 것과 심사가 없는 것은 다른 사실이다</b>
     */
    public Optional<FraudReviewDraft> draftFor(long reviewId) {
        return facts.factsOf(reviewId).map(this::build);
    }

    private FraudReviewDraft build(FraudReviewFacts f) {
        Optional<String> text = primary.draft(f);

        if (text.isEmpty()) {
            return fallback(f, "no_draft");
        }

        List<String> bad = guard.verify(text.get(), allowed(f));
        if (!bad.isEmpty()) {
            // 걸린 값을 로그에 남긴다. 무엇을 지어냈는지 안 남기면 프롬프트를 못 고친다.
            log.warn("[fraud-review] 출처 없는 값으로 초안을 버립니다 review={} bad={}", f.reviewId(), bad);
            return fallback(f, "guard_rejected");
        }

        count("ok", primary.name());
        return new FraudReviewDraft(f.reviewId(), text.get(), primary.name(), List.of());
    }

    /**
     * 템플릿으로 떨어뜨린다. <b>그 템플릿도 같은 가드를 통과해야 나간다.</b>
     *
     * <p>템플릿은 사실만 옮기니 통과가 당연한데, 그래서 더 걸어야 한다. 템플릿이 바뀌어
     * 사실에 없는 값을 넣기 시작하면 그때 걸려야 한다.
     */
    private FraudReviewDraft fallback(FraudReviewFacts f, String reason) {
        count(reason, template.name());
        Optional<String> fb = template.draft(f);
        if (fb.isEmpty()) {
            return new FraudReviewDraft(f.reviewId(), null, "none", List.of(reason, "template_empty"));
        }
        List<String> bad = guard.verify(fb.get(), allowed(f));
        if (!bad.isEmpty()) {
            log.error("[fraud-review] 템플릿이 가드에 걸렸습니다 review={} bad={}", f.reviewId(), bad);
            return new FraudReviewDraft(f.reviewId(), null, "none", List.of(reason, "template_rejected"));
        }
        return new FraudReviewDraft(f.reviewId(), fb.get(), template.name(), List.of(reason));
    }

    /**
     * 가드가 허용할 값의 전부.
     *
     * <p>{@code NumericProvenanceGuard} 는 <b>{@code 원} 이 붙은 금액과 날짜만</b> 검사한다.
     * 점수와 비율은 {@code 원} 이 없어 그대로 지나간다. 그 경계를 넓히면 "규칙 3개" 같은
     * 세는 말까지 반려되고, 좁히면 지어낸 금액이 샌다.
     */
    private FactPack allowed(FraudReviewFacts f) {
        Set<Long> amounts = new LinkedHashSet<>();
        amounts.add(f.amount());
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (f.detectedAt() != null) {
            dates.add(f.detectedAt().atZone(KST).toLocalDate());
        }
        return new FactPack(f.orderNo(), List.of(), amounts, dates, null, true);
    }

    private void count(String outcome, String source) {
        Counter.builder(METRIC)
                .tag("outcome", outcome)
                .tag("source", source)
                .register(registry)
                .increment();
    }
}
