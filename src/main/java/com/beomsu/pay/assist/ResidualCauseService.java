package com.beomsu.pay.assist;

import com.beomsu.pay.reconciliation.CauseSuggestion;
import com.beomsu.pay.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 규칙 분류기가 못 가른 건에만 모델을 붙인다. 여섯 개의 가드를 순서대로 건다.
 *
 * <p><b>1. 규칙이 아무것도 못 냈을 때만 부른다.</b> 원인 아홉 종 가운데 여섯은 산수와
 * 조회로 결정된다. 이미 답이 있는 것을 모델에게 추측시키면 나아지는 게 없고 틀릴 자리만 는다.
 *
 * <p><b>2. 출력은 {@link ResolveCause} 안의 값만 받는다.</b> Ramp가 거래 재분류
 * 에이전트에 건 후처리 가드레일과 같다. 목록 밖의 값을 고르면 그 응답을 버린다.
 *
 * <p><b>3. {@link ResolveCause#SUSPECTED_TAMPERING}은 제안 자체를 금지한다.</b>
 * 위변조 의심은 유형으로 배제한다. 이 자리에서 추측이 제일 비싸다.
 *
 * <p><b>4. 신뢰도가 임계 미만이면 기권으로 처리한다.</b> 고위험 분류의 표준 장치인
 * selective prediction 이다. 커버리지를 정확도와 맞바꾼다. {@code OTHER}도 기권으로 친다 —
 * "모르겠다"를 원인 코드로 남기면 집계가 오염된다.
 *
 * <p><b>5. 근거 문장의 숫자는 {@link NumericProvenanceGuard}로 대조한다.</b> 상담 초안과
 * 같은 계약이다. 코드가 낸 값이 아닌 금액·날짜가 하나라도 있으면 제안을 통째로 버린다.
 *
 * <p><b>6. {@code resolve}는 하지 않는다.</b> 이 서비스는 후보를 돌려줄 뿐이고, 확정은
 * 사람이 화면에서 한다. 자동 확정은 그 유형의 실측 오류율이 쌓인 뒤에 따로 결정할 문제다.
 *
 * <p>기본값은 꺼짐이다. {@code app.assist.residual.enabled=true}일 때만 모델을 부른다.
 */
@Service
@RequiredArgsConstructor
public class ResidualCauseService {

    private static final Logger log = LoggerFactory.getLogger(ResidualCauseService.class);

    /** 가드 3 — 모델이 절대 고를 수 없는 원인. */
    static final EnumSet<ResolveCause> FORBIDDEN =
            EnumSet.of(ResolveCause.SUSPECTED_TAMPERING, ResolveCause.OTHER);

    private final DraftService draftService;
    private final NumericProvenanceGuard numberGuard;
    private final Optional<ResidualCausePort> port;
    private final MeterRegistry registry;

    @Value("${app.assist.residual.enabled:false}")
    private boolean enabled;

    /** 가드 4 — 이 값 미만이면 기권. */
    @Value("${app.assist.residual.min-confidence:70}")
    private int minConfidence;

    /**
     * 규칙이 못 가른 건에 원인 후보를 제안한다.
     *
     * @param rules 규칙 분류기가 낸 제안. <b>비어 있지 않으면 아무것도 하지 않는다</b>
     * @return 후보. 기권했거나 가드에 걸렸으면 {@link Optional#empty()}
     */
    public Optional<ResidualSuggestion> suggest(String orderNo, Long reconResultId,
                                                List<CauseSuggestion> rules) {
        if (!enabled || port.isEmpty()) {
            return Optional.empty();
        }
        if (rules != null && !rules.isEmpty()) {
            count("skipped_rules_decided");     // 가드 1
            return Optional.empty();
        }

        FactPack facts = draftService.factsFor(orderNo, reconResultId);
        if (facts.facts().isEmpty()) {
            count("no_facts");
            return Optional.empty();
        }

        Optional<ResidualSuggestion> raw;
        try {
            raw = port.get().suggest(facts);
        } catch (RuntimeException e) {
            count("error");
            log.warn("[residual] 후보 생성 실패 order={} recon={}", orderNo, reconResultId, e);
            return Optional.empty();
        }

        if (raw.isEmpty()) {
            count("abstained");                 // 모델이 스스로 기권
            return Optional.empty();
        }
        ResidualSuggestion s = raw.get();

        if (s.cause() == null || FORBIDDEN.contains(s.cause())) {   // 가드 2·3
            count("forbidden_cause");
            log.info("[residual] 금지된 원인 제안을 버림 order={} cause={}", orderNo, s.cause());
            return Optional.empty();
        }
        if (s.confidence() < minConfidence) {                       // 가드 4
            count("below_threshold");
            return Optional.empty();
        }

        List<String> unsourced = numberGuard.verify(s.rationale(), facts);   // 가드 5
        if (!unsourced.isEmpty()) {
            count("unsourced_figures");
            log.info("[residual] 출처 없는 숫자로 제안을 버림 order={} rejected={}", orderNo, unsourced);
            return Optional.empty();
        }

        count("suggested");
        log.info("[residual] 후보 order={} cause={} confidence={} source={}",
                orderNo, s.cause(), s.confidence(), port.get().name());
        return Optional.of(s);
    }

    private void count(String outcome) {
        registry.counter("assist.residual.outcome", "outcome", outcome).increment();
    }
}
